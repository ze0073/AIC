package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.PerformanceAssessment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Service
public class RunComparisonService {
    private final TestRunService runs;
    private final ObjectMapper json;

    public RunComparisonService(TestRunService runs, ObjectMapper json) { this.runs = runs; this.json = json; }

    public Map<String,Object> compare(String runId, String baselineRunId) {
        TestRunService.RunView current = runs.get(runId);
        TestRunService.RunView baseline = runs.get(baselineRunId);
        return compareRuns(current,baseline);
    }

    /** Only for task-owned run IDs already checked against the selected baseline task. */
    public Map<String,Object> compareAgent(String runId, String baselineRunId) {
        return compareRuns(runs.agentGet(runId),runs.agentGet(baselineRunId));
    }

    private Map<String,Object> compareRuns(TestRunService.RunView current, TestRunService.RunView baseline) {
        if (!current.projectId().equals(baseline.projectId())) throw ApiException.notFound("基线运行不存在");
        String runId=current.id(),baselineRunId=baseline.id();
        List<String> reasons = new ArrayList<>();
        if (runId.equals(baselineRunId)) reasons.add("不能与同一次运行比较");
        boolean hosted=com.shijie.accessibility.hosting.HostedRunIdentity.sameSite(read(current.configSnapshot()),read(baseline.configSnapshot()));
        if (!hosted&&!current.targetId().equals(baseline.targetId())) reasons.add("目标不同");
        if (!current.suiteVersionId().equals(baseline.suiteVersionId())) reasons.add("套件版本不同");
        Map<String,Object> currentConfig = read(current.configSnapshot());
        Map<String,Object> baselineConfig = read(baseline.configSnapshot());
        Object currentLoad = loadConfig(currentConfig), baselineLoad = loadConfig(baselineConfig);
        if (!Objects.equals(currentLoad, baselineLoad)) reasons.add("负载配置不同");
        if (!Objects.equals(Boolean.TRUE.equals(currentConfig.get("agentSafe")),Boolean.TRUE.equals(baselineConfig.get("agentSafe")))
                || !Objects.equals(Boolean.TRUE.equals(currentConfig.get("agentIncludeFlow")),Boolean.TRUE.equals(baselineConfig.get("agentIncludeFlow"))))
            reasons.add("智能体执行范围不同");
        Map<String,Object> modules = new LinkedHashMap<>();
        for (String type : List.of("FRONTEND","SECURITY","PERFORMANCE")) {
            TestRunService.ModuleView now = module(current,type), before = module(baseline,type);
            List<String> unavailable = new ArrayList<>(reasons);
            if ("SECURITY".equals(type)) {
                if (!Objects.equals(Boolean.TRUE.equals(currentConfig.get("activeScan")),Boolean.TRUE.equals(baselineConfig.get("activeScan"))))
                    unavailable.add("主动扫描开关不同");
                if (uploadEnabled(currentConfig)!=uploadEnabled(baselineConfig))
                    unavailable.add("上传检查开关不同");
            }
            if (now == null || before == null) unavailable.add("有一次运行未选择该模块");
            else if (!"COMPLETED".equals(now.status()) || !"COMPLETED".equals(before.status()))
                unavailable.add("对应模块未在两次运行中完整执行");
            if("SECURITY".equals(type)&&now!=null&&before!=null&&
                    (uncertain(current,now)||uncertain(baseline,before))) unavailable.add("安全检查存在无法判断的用例");
            if("PERFORMANCE".equals(type)&&now!=null&&before!=null){
                if(!validPerformance(now))unavailable.add("本次运行缺少有效响应耗时，可能所有请求失败或指标不完整");
                if(!validPerformance(before))unavailable.add("基线运行缺少有效响应耗时，可能所有请求失败或指标不完整");
            }
            if (!unavailable.isEmpty()) { modules.put(type, Map.of("comparable",false,"reasons",unavailable)); continue; }
            Map<String,Object> previous = metrics(type, read(before.resultPayload()),baseline,before);
            Map<String,Object> latest = metrics(type, read(now.resultPayload()),current,now);
            if (previous.isEmpty() || latest.isEmpty()) {
                modules.put(type,Map.of("comparable",false,"reasons",List.of("指标缺失或无法解析")));
                continue;
            }
            Map<String,Object> deltas = new LinkedHashMap<>();
            for (String key : previous.keySet()) {
                Object a=previous.get(key), b=latest.get(key);
                if (a instanceof Number x && b instanceof Number y) deltas.put(key,y.doubleValue()-x.doubleValue());
                if (a instanceof Map<?,?> oldPages && b instanceof Map<?,?> newPages) {
                    Map<String,Object> pageDiff=new LinkedHashMap<>();
                    for (Object page : oldPages.keySet()) {
                        Object old=oldPages.get(page), newer=newPages.get(page);
                        if (old instanceof Map<?,?> oldValues && newer instanceof Map<?,?> newValues) {
                            Map<String,Double> diff=new LinkedHashMap<>();
                            for (Object field : oldValues.keySet()) if (oldValues.get(field) instanceof Number v && newValues.get(field) instanceof Number w)
                                diff.put(String.valueOf(field),w.doubleValue()-v.doubleValue());
                            pageDiff.put(String.valueOf(page),diff);
                        }
                    }
                    deltas.put(key,pageDiff);
                }
            }
            modules.put(type,Map.of("comparable",true,"baseline",previous,"current",latest,"delta",deltas));
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("runId",runId);result.put("baselineRunId",baselineRunId);
        result.put("comparable",reasons.isEmpty());result.put("reasons",reasons);result.put("modules",modules);return result;
    }

    public Map<String,Object> agentMetrics(TestRunService.RunView run,String type) {
        TestRunService.ModuleView selected=module(run,type);
        if(selected==null)return Map.of();
        return metrics(type,read(selected.resultPayload()),run,selected);
    }

    private Object loadConfig(Map<String,Object> snapshot) {
        if (!(snapshot.get("suite") instanceof Map<?,?> suite) || !(suite.get("definition") instanceof Map<?,?> definition)) return null;
        return definition.get("performance");
    }
    static boolean uploadEnabled(Map<String,Object> snapshot) {
        return !Boolean.TRUE.equals(snapshot.get("agentSafe"))
                &&(!"DAILY_REGRESSION".equals(snapshot.get("schedule")) || Boolean.TRUE.equals(snapshot.get("uploadEnabled")));
    }
    private TestRunService.ModuleView module(TestRunService.RunView run,String type) {
        return run.modules().stream().filter(value->type.equals(value.moduleType())).findFirst().orElse(null);
    }
    private boolean uncertain(TestRunService.RunView run,TestRunService.ModuleView module){
        return run.issues().stream().anyMatch(issue->module.id().equals(issue.moduleId())&&"INCONCLUSIVE".equals(issue.evidenceConfidence()));
    }
    private boolean validPerformance(TestRunService.ModuleView module){
        Map<String,Object> payload=read(module.resultPayload());
        Map<String,Object> summary=payload.get("summary") instanceof Map<?,?> value?readMap(value):Map.of();
        return PerformanceAssessment.evaluate(summary,Map.of(),module.status()).latencyAvailable();
    }
    private Map<String,Object> metrics(String type,Map<String,Object> payload,TestRunService.RunView run,TestRunService.ModuleView module) {
        if ("FRONTEND".equals(type)) {
            Object value=payload.get("measurements");if (!(value instanceof List<?> measurements)) return Map.of();
            Map<String,Map<String,List<Double>>> grouped=new LinkedHashMap<>();
            for(Object item:measurements) if(item instanceof Map<?,?> row && row.get("pageUrl")!=null) {
                String page=String.valueOf(row.get("pageUrl"));
                if(read(run.configSnapshot()).containsKey("hosting")){try{java.net.URI uri=java.net.URI.create(page);page=uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());}catch(Exception ignored){}}
                Map<String,List<Double>> fields=grouped.computeIfAbsent(page,unused->new LinkedHashMap<>());
                for(String field:List.of("ttfbMs","navigationMs","lcpMs","cls","requestCount","declaredResourceBytes"))
                    if(row.get(field) instanceof Number n) fields.computeIfAbsent(field,unused->new ArrayList<>()).add(n.doubleValue());
            }
            if(grouped.isEmpty()||grouped.values().stream().anyMatch(fields->fields.getOrDefault("navigationMs",List.of()).isEmpty()))return Map.of();
            Map<String,Object> pages=new LinkedHashMap<>();
            grouped.forEach((page,fields)->{Map<String,Double> values=new LinkedHashMap<>();fields.forEach((field,samples)->values.put(field,samples.stream().mapToDouble(Double::doubleValue).average().orElse(0)));pages.put(page,values);});
            return Map.of("pages",pages);
        }
        if ("SECURITY".equals(type)) {
            List<TestRunService.IssueView> issues=run.issues().stream().filter(issue->module.id().equals(issue.moduleId())).toList();
            return Map.of("issueCount",issues.size(),"humanConfirmedCount",issues.stream().filter(issue->"CONFIRMED".equals(issue.reviewStatus())).count());
        }
        if (!(payload.get("summary") instanceof Map<?,?> summary) || !(summary.get("metrics") instanceof Map<?,?> metrics))return Map.of();
        Map<String,Object> result=new LinkedHashMap<>();
        extractTrend(metrics,"http_req_duration",result);
        if(metrics.get("http_reqs") instanceof Map<?,?> requests && requests.get("rate") instanceof Number n)result.put("throughputPerSecond",n.doubleValue());
        if(metrics.get("http_req_failed") instanceof Map<?,?> failures && failures.get("value") instanceof Number n)result.put("errorRate",n.doubleValue());
        return result.keySet().containsAll(List.of("p50Ms","p95Ms","p99Ms","throughputPerSecond","errorRate"))?result:Map.of();
    }
    private void extractTrend(Map<?,?> metrics,String name,Map<String,Object> result) {
        if (!(metrics.get(name) instanceof Map<?,?> trend)) return;
        Map<?,?> values=trend.get("values") instanceof Map<?,?> nested?nested:trend;
        for(String percentile:List.of("med","p(95)","p(99)"))if(values.get(percentile) instanceof Number n)
            result.put(switch(percentile){case "med"->"p50Ms";case "p(95)"->"p95Ms";default->"p99Ms";},n.doubleValue());
    }
    @SuppressWarnings("unchecked") private Map<String,Object> read(String value){
        try{return value==null?Map.of():(Map<String,Object>)json.readValue(value,Map.class);}catch(Exception ignored){return Map.of();}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> readMap(Map<?,?> value){return (Map<String,Object>)value;}
}
