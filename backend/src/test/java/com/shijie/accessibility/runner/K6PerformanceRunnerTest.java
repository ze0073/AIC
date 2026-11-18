package com.shijie.accessibility.runner;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.online.TargetScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class K6PerformanceRunnerTest {
    @TempDir Path temporary;
    @Test void successfulProcessWithoutRequestMetricsIsNotACompletedLoadTest() throws Exception {
        TargetScopeService scope=new TargetScopeService();
        var registered=scope.previewRegistration("http://127.0.0.1/");
        var target=new OnlineTargetService.TargetView("target","project","local",registered.baseUrl(),registered.origin(),
                registered.addresses(),registered.pathPrefixes(),false,false,null,null,null);
        var properties=new AppProperties(temporary,new AppProperties.Scanner(10,10_000,60_000),AppProperties.Ai.defaults(false));
        ToolProcessRunner process=mock(ToolProcessRunner.class);
        when(process.run(anyString(),anyList(),any(Path.class),any(java.time.Duration.class),any()))
                .thenReturn(new ToolProcessRunner.Result("COMPLETED",0,"",""));
        var checked=new K6PerformanceRunner(process,new ObjectMapper(),properties,scope,"qa-k6");
        assertEquals("FAILED",checked.run("00000000-missing",target,Map.of("vus",List.of(1),"stageDurationSeconds",1)).status());
        Path summary=temporary.resolve("test-runs/00000000-valid/k6/summary.json");
        Files.createDirectories(summary.getParent());
        Files.writeString(summary,"{\"metrics\":{\"http_reqs\":{\"count\":2},\"http_req_duration\":{},\"http_req_failed\":{}}}");
        assertEquals("FAILED",checked.run("00000000-valid",target,Map.of("vus",List.of(1),"stageDurationSeconds",1)).status());
        Files.writeString(summary,"{\"metrics\":{\"http_reqs\":{\"count\":2},\"http_req_duration\":{\"med\":8,\"p(95)\":10,\"p(99)\":11},\"http_req_failed\":{\"value\":0}}}");
        assertEquals("COMPLETED",checked.run("00000000-valid",target,Map.of("vus",List.of(1),"stageDurationSeconds",1)).status());
    }

    @Test void configuredRequestRateIsEnforcedByPerVuPacing() {
        var runner=new K6PerformanceRunner(null,null,null,null,"grafana/k6:0.57.0");
        String script=runner.script(List.of(1,5,20),20,1000,.01,5);
        assertTrue(script.contains("sleep(4.000)"));
        assertTrue(script.contains("target:20"));
        assertTrue(script.contains("startVUs:1"),"A single-user stage must produce real requests");
        assertTrue(script.contains("redirects:0"));
        assertTrue(script.contains("x.status>=200&&x.status<400"));
        assertTrue(runner.script(List.of(1,5,20),20,1000,.01,50).contains("sleep(0.400)"));
    }

    @Test void emptyLoadPathKeepsTheExactRegisteredEntryPage() {
        TargetScopeService scope=new TargetScopeService();
        var registered=scope.previewRegistration("http://127.0.0.1/app/index.html");
        var target=new OnlineTargetService.TargetView("target","project","local",registered.baseUrl(),registered.origin(),
                registered.addresses(),registered.pathPrefixes(),false,false,null,null,null);
        var properties=new AppProperties(temporary,new AppProperties.Scanner(10,10_000,60_000),AppProperties.Ai.defaults(false));
        ToolProcessRunner process=mock(ToolProcessRunner.class);
        when(process.run(anyString(),anyList(),any(Path.class),any(java.time.Duration.class),any()))
                .thenReturn(new ToolProcessRunner.Result("FAILED",1,"","missing test tool"));
        var runner=new K6PerformanceRunner(process,new ObjectMapper(),properties,scope,"qa-k6");
        runner.run("00000000-entry",target,Map.of("path",""));
        verify(process).run(eq("00000000-entry"),argThat(args -> args.contains("TARGET_URL="+registered.baseUrl())),
                any(Path.class),any(java.time.Duration.class),any());
    }

    @Test void abortsOnlyAfterTwoConsecutiveCompleteHighErrorWindows() throws Exception {
        var runner=new K6PerformanceRunner(null,new ObjectMapper(),null,null,"grafana/k6:0.57.0");
        Path samples=temporary.resolve("samples.json");
        Instant at=Instant.parse("2026-09-23T00:00:00Z");
        Files.writeString(samples,String.join("\n",
                sample(at,0),sample(at,11),sample(at,21))+"\n");
        long started=System.nanoTime()-java.time.Duration.ofSeconds(31).toNanos();
        assertTrue(runner.shouldAbort(samples,started,20,1000,.01));
        Files.writeString(samples,String.join("\n",sample(at,0),sample(at,11),sample(at,23,false))+"\n");
        assertFalse(runner.shouldAbort(samples,started,20,1000,.01));
    }

    private String sample(Instant at,int seconds){return sample(at,seconds,true);}
    private String sample(Instant at,int seconds,boolean failed){
        return "{\"type\":\"Point\",\"metric\":\"http_req_failed\",\"data\":{\"time\":\""+at.plusSeconds(seconds)+"\",\"value\":"+(failed?1:0)+"}}";
    }
}
