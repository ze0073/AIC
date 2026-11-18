package com.shijie.accessibility.runner;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.common.PerformanceAssessment;
import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.online.TargetScopeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@Component
public class K6PerformanceRunner {
    private final ToolProcessRunner processes;
    private final ObjectMapper json;
    private final AppProperties properties;
    private final TargetScopeService scope;
    private final String image;

    public K6PerformanceRunner(ToolProcessRunner processes, ObjectMapper json, AppProperties properties, TargetScopeService scope,
                               @Value("${app.tools.k6-image:grafana/k6:0.57.0}") String image) {
        this.processes = processes; this.json = json; this.properties = properties; this.scope=scope; this.image = image;
    }

    public Output run(String runId, OnlineTargetService.TargetView target, Map<String, Object> config) {
        int p95 = bounded(number(config, "p95Ms", 1000), 50, 60_000);
        double errorRate = Math.max(0.0001, Math.min(0.5, decimal(config, "errorRate", 0.01)));
        int duration = bounded(number(config, "stageDurationSeconds", 20), 1, 40);
        List<Integer> vus = intList(config.get("vus"), List.of(1, 5, 10)).stream().map(v -> bounded(v, 1, 20)).limit(3).toList();
        int rate = bounded(number(config,"ratePerSecond",50),1,50);
        String requestedPath = Objects.toString(config.get("path"), "");
        String targetUrl = scope.requireAllowed(target, requestedPath.isBlank() ? target.baseUrl()
                : java.net.URI.create(target.baseUrl()).resolve(requestedPath).toString()).toString();
        Path work = properties.storageRoot().toAbsolutePath().normalize().resolve("test-runs").resolve(runId).resolve("k6");
        try {
            Files.createDirectories(work);
            Files.writeString(work.resolve("script.js"), script(vus, duration, p95, errorRate,rate));
            String mount = work.toString().replace('\\', '/');
            List<String> command = List.of("docker", "run", "--rm", "--pull=never", "--add-host",java.net.URI.create(target.baseUrl()).getHost()+":"+scope.toolAddress(target), "--name", "shijie-k6-" + runId.substring(0, 8).toLowerCase(),
                    "--memory", "512m", "--cpus", "1", "-v", mount + ":/work:rw", "-e", "TARGET_URL=" + targetUrl,
                    "-e","GATEWAY_ACCESS="+scope.requestHeaders(target).getOrDefault("X-Shijie-Access",""),image, "run", "--summary-export=/work/summary.json", "--summary-trend-stats=avg,min,med,max,p(90),p(95),p(99)",
                    "--out", "json=/work/samples.json", "/work/script.js");
            long started=System.nanoTime();
            ToolProcessRunner.Result result = processes.run(runId, command, work, Duration.ofSeconds(Math.min(120, duration * vus.size()) + 25L),
                    ()->shouldAbort(work.resolve("samples.json"),started,duration,p95,errorRate));
            Map<String, Object> summary = new LinkedHashMap<>(readSummary(work.resolve("summary.json")));
            List<Map<String,Object>> series=readSeries(work.resolve("samples.json"),duration,p95,errorRate);
            summary.put("timeSeries",series);
            series.stream().filter(point->Boolean.TRUE.equals(point.get("thresholdExceeded"))).findFirst()
                    .ifPresent(point->summary.put("firstExceededStage",point.get("stage")));
            PerformanceAssessment assessment=PerformanceAssessment.evaluate(summary,config,"COMPLETED");
            boolean completeData=assessment.latencyAvailable();
            String status = result.exitCode()==99 && completeData && "FAILED".equals(result.status()) ? "COMPLETED" : "ABORTED".equals(result.status())?"PARTIAL":result.status();
            if("COMPLETED".equals(status)&&!completeData)status="FAILED";
            String error="FAILED".equals(status)&&(result.exitCode()==0||result.exitCode()==99)&&!completeData
                    ?assessment.reason():result.exitCode()==99&&completeData?null:result.error();
            return new Output(status, image, result.exitCode(), summary, result.output(), error);
        } catch (Exception ex) {
            return new Output("FAILED", image, -1, Map.of(), "", ex.getMessage());
        }
    }

    public String image() { return image; }
    public String digest() { return processes.imageDigest(image); }
    public void cancel(String runId) { processes.cancel(runId); }

    String script(List<Integer> vus, int duration, int p95, double errorRate,int rate) {
        String stages = vus.stream().map(v -> "{duration:'" + duration + "s',target:" + v + "}").reduce((a,b) -> a + "," + b).orElse("");
        int maxVus = vus.stream().mapToInt(Integer::intValue).max().orElse(1);
        double minInterval = Math.max(0.02,(double)maxVus/rate);
        return String.format(java.util.Locale.ROOT,"""
            import http from 'k6/http';
            import { check, sleep } from 'k6';
            export const options = {
              scenarios:{load:{executor:'ramping-vus',startVUs:1,stages:[%s],gracefulRampDown:'2s'}},
              thresholds:{http_req_duration:[{threshold:'p(95)<=%d',abortOnFail:false}],http_req_failed:[{threshold:'rate<%s',abortOnFail:false}]},
              discardResponseBodies:true
            };
            export default function(){ const r=http.get(__ENV.TARGET_URL,{redirects:0,headers:__ENV.GATEWAY_ACCESS?{'X-Shijie-Access':__ENV.GATEWAY_ACCESS}:{},tags:{suite:'shijie-v061'}}); check(r,{'valid HTTP response below 400':x=>x.status>=200&&x.status<400}); sleep(%.3f); }
            """,stages, p95, errorRate,minInterval);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSummary(Path path) {
        try { return Files.exists(path) ? (Map<String, Object>) json.readValue(Files.readString(path), Map.class) : Map.of(); }
        catch (Exception ex) { return Map.of("parseError", ex.getMessage()); }
    }
    boolean shouldAbort(Path samples,long started,int stageDuration,int p95,double allowedErrorRate){
        long elapsedSeconds=Duration.ofNanos(System.nanoTime()-started).toSeconds();
        if(elapsedSeconds<30)return false;
        long completeBefore=(elapsedSeconds/10)*10;
        List<Map<String,Object>> complete=readSeries(samples,stageDuration,p95,allowedErrorRate).stream()
                .filter(point->point.get("windowStartSecond") instanceof Number n&&n.longValue()>=10&&n.longValue()+10<=completeBefore)
                .filter(point->point.get("sampleCount") instanceof Number n&&n.longValue()>0).toList();
        if(complete.size()<2)return false;
        int last=complete.size()-1;
        return ((Number)complete.get(last).get("errorRate")).doubleValue()>.20
                &&((Number)complete.get(last-1).get("errorRate")).doubleValue()>.20
                &&((Number)complete.get(last).get("windowStartSecond")).longValue()
                  -((Number)complete.get(last-1).get("windowStartSecond")).longValue()==10;
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> readSeries(Path path,int stageDuration,int p95,double allowedErrorRate){
        if(!Files.exists(path))return List.of();
        Map<Long,Window> windows=new TreeMap<>();
        Instant[] first={null};
        try(var lines=Files.lines(path)){
            lines.limit(300_000).forEach(line->{
                try{
                    Map<String,Object> point=(Map<String,Object>)json.readValue(line,Map.class);
                    if(!"Point".equals(point.get("type")))return;
                    String metric=String.valueOf(point.get("metric"));
                    if(!Set.of("http_req_duration","http_req_failed","http_reqs").contains(metric))return;
                    Map<String,Object> data=(Map<String,Object>)point.get("data");
                    Instant at=OffsetDateTime.parse(String.valueOf(data.get("time"))).toInstant();
                    if(first[0]==null||at.isBefore(first[0]))first[0]=at;
                    long slot=Math.max(0,Duration.between(first[0],at).toSeconds()/10);
                    Window window=windows.computeIfAbsent(slot,unused->new Window());
                    double value=((Number)data.get("value")).doubleValue();
                    if("http_req_duration".equals(metric))window.durations.add(value);
                    else if("http_req_failed".equals(metric)){window.samples++;if(value>0)window.failures++;}
                    else window.requests+=value;
                }catch(Exception ignored){}
            });
        }catch(Exception ex){return List.of(Map.of("status","UNAVAILABLE","error",ex.getClass().getSimpleName()));}
        List<Map<String,Object>> series=new ArrayList<>();
        for(var entry:windows.entrySet()){
            long slot=entry.getKey();Window window=entry.getValue();window.durations.sort(Double::compareTo);
            double errorRate=window.samples==0?0:(double)window.failures/window.samples;
            double windowP95=percentile(window.durations,.95);
            Map<String,Object> point=new LinkedHashMap<>();point.put("windowStartSecond",slot*10);point.put("stage",Math.min(3,(int)(slot*10/stageDuration)+1));
            point.put("requestCount",window.requests);point.put("throughputPerSecond",window.requests/10.0);
            point.put("sampleCount",window.samples);
            point.put("p50Ms",percentile(window.durations,.50));point.put("p95Ms",windowP95);point.put("p99Ms",percentile(window.durations,.99));
            point.put("errorRate",errorRate);point.put("thresholdExceeded",!window.durations.isEmpty()&&windowP95>p95||window.samples>0&&errorRate>=allowedErrorRate);
            series.add(point);
        }
        return series;
    }
    private double percentile(List<Double> sorted,double percentile){if(sorted.isEmpty())return 0;return sorted.get(Math.min(sorted.size()-1,Math.max(0,(int)Math.ceil(sorted.size()*percentile)-1)));}
    private static class Window {final List<Double> durations=new ArrayList<>();int samples;int failures;double requests;}
    private int number(Map<String, Object> config, String key, int fallback) { Object value=config.get(key); return value instanceof Number n?n.intValue():fallback; }
    private double decimal(Map<String, Object> config, String key, double fallback) { Object value=config.get(key); return value instanceof Number n?n.doubleValue():fallback; }
    private int bounded(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private List<Integer> intList(Object value, List<Integer> fallback) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return fallback;
        return list.stream().filter(Number.class::isInstance).map(Number.class::cast).map(Number::intValue).toList();
    }
    public record Output(String status, String image, int exitCode, Map<String, Object> summary, String logTail, String error) {}
}
