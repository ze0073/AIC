package com.shijie.accessibility.runner;

import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ZapActiveScanRunnerTest {
    @TempDir Path temporary;
    private final ZapActiveScanRunner runner = new ZapActiveScanRunner(null, null, new com.shijie.accessibility.online.TargetScopeService(),new ObjectMapper(), "zaproxy/zap-stable:2.16.1");

    @Test void successfulProcessWithoutAValidReportIsNotACompletedScan() throws Exception {
        var scope=new com.shijie.accessibility.online.TargetScopeService();
        var registered=scope.previewRegistration("http://127.0.0.1/");
        var target=new OnlineTargetService.TargetView("target","project","local",registered.baseUrl(),registered.origin(),
                registered.addresses(),registered.pathPrefixes(),true,false,null,null,null);
        var properties=new AppProperties(temporary,new AppProperties.Scanner(10,10_000,60_000),AppProperties.Ai.defaults(false));
        ToolProcessRunner process=mock(ToolProcessRunner.class);
        when(process.run(anyString(),anyList(),any(Path.class),eq(Duration.ofMinutes(10))))
                .thenReturn(new ToolProcessRunner.Result("COMPLETED",0,"",""));
        var checked=new ZapActiveScanRunner(process,properties,scope,new ObjectMapper(),"qa-zap");
        var cases=List.<Map<String,Object>>of(Map.of("method","GET","path","/search?q=test","activeScan",true,"activeParameters",List.of("q")));
        assertEquals("FAILED",checked.run("00000000-missing",target,cases).status());
        Path report=temporary.resolve("test-runs/00000000-valid/zap/zap-report.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report,"{\"site\":[{\"@host\":\"127.0.0.1\",\"alerts\":[]}]}");
        assertEquals("COMPLETED",checked.run("00000000-valid",target,cases).status());
    }

    @Test void onlyExplicitGetParametersAreEligible() {
        assertTrue(runner.eligible(Map.of("method","GET","path","/api/search?keyword=x","activeScan",true,"activeParameters",List.of("keyword"))));
        assertFalse(runner.eligible(Map.of("method","POST","path","/api/search?keyword=x","activeScan",true,"activeParameters",List.of("keyword"))));
        assertFalse(runner.eligible(Map.of("method","GET","path","/api/search?keyword=x&other=y","activeScan",true,"activeParameters",List.of("keyword"))));
    }

    @Test void generatedPlanSeedsRequestsAndLimitsScanRate() {
        var target = new OnlineTargetService.TargetView("t", "p", "demo", "http://demo:8081/", "http://demo:8081",
                List.of("127.0.0.1"), List.of("/"), true, false, null, null, null);
        String plan=runner.plan(target,List.of(Map.of("path","/api/search?keyword=x")));
        assertTrue(plan.contains("  - type: requestor\n    requests:\n      - url: 'http://demo:8081/api/search?keyword=x'\n        method: GET\n  - type: activeScan-policy"),plan);
        assertTrue(plan.contains("threadPerHost: 1"));
        assertTrue(plan.contains("delayInMs: 500"));
        assertTrue(plan.contains("defaultThreshold: 'OFF'"));
        assertTrue(plan.contains("name: shijie-scope-guard"));
        assertTrue(plan.contains("failOnError: true"));
        assertTrue(runner.scopeGuard(target).contains("Blocked out-of-scope request before network send"));
        String qaDirectory=System.getProperty("zap.qa.dir");
        if(qaDirectory!=null)try{
            java.nio.file.Path directory=java.nio.file.Path.of(qaDirectory);java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve("automation.yaml"),plan);
            java.nio.file.Files.writeString(directory.resolve("scope-guard.js"),runner.scopeGuard(target));
            var live=new OnlineTargetService.TargetView("t","p","qa","http://campus-demo:8091/api/","http://campus-demo:8091",List.of("172.18.0.4"),List.of("/api/"),true,false,null,null,null);
            String livePlan=runner.plan(live,List.of(Map.of("path","/api/greeting?name=test")));
            String requestOnly=livePlan.substring(0,livePlan.indexOf("  - type: activeScan-policy"));
            java.nio.file.Files.writeString(directory.resolve("allowed.yaml"),requestOnly);
            java.nio.file.Files.writeString(directory.resolve("blocked.yaml"),requestOnly.replace("      - url: 'http://campus-demo:8091/api/greeting?name=test'","      - url: 'http://campus-demo:8091/blocked-outside-api?name=test'"));
            java.nio.file.Files.writeString(directory.resolve("cross-origin.yaml"),requestOnly.replace("      - url: 'http://campus-demo:8091/api/greeting?name=test'","      - url: 'http://out-of-scope.invalid:8091/api/health'"));
            java.nio.file.Files.writeString(directory.resolve("redirect.yaml"),requestOnly.replace("      - url: 'http://campus-demo:8091/api/greeting?name=test'","      - url: 'http://campus-demo:8091/api/scope-redirect'"));
            StringBuilder matrixRequests=new StringBuilder();
            for(int status:List.of(301,302,303,307,308))for(String kind:List.of("inside","path","host","port","scheme"))
                matrixRequests.append("      - url: 'http://campus-demo:8091/api/scope-redirect/")
                        .append(kind).append("?code=").append(status).append("'\n        method: GET\n");
            String seed="      - url: 'http://campus-demo:8091/api/greeting?name=test'\n        method: GET";
            java.nio.file.Files.writeString(directory.resolve("redirect-matrix.yaml"),
                    requestOnly.replace(seed,matrixRequests.toString().stripTrailing()));
            List<Map<String,Object>> redirectCases=List.of("inside","path","host","port","scheme").stream()
                    .map(kind->Map.<String,Object>of("path","/api/scope-redirect/"+kind+"?code=302",
                            "method","GET","activeScan",true,"activeParameters",List.of("code"))).toList();
            java.nio.file.Files.writeString(directory.resolve("active-redirect-matrix.yaml"),runner.plan(live,redirectCases));
            java.nio.file.Files.writeString(directory.resolve("scope-guard.js"),runner.scopeGuard(live));
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
