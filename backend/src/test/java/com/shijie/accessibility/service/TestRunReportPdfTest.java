package com.shijie.accessibility.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestRunReportPdfTest {
    @TempDir Path temporary;

    @Test void rendersReadableSummaryWithoutInliningScreenshotBytes() throws Exception {
        ObjectMapper json=new ObjectMapper();
        List<Map<String,Object>> issues=new ArrayList<>();
        for(int n=1;n<=12;n++)issues.add(Map.of("severity","HIGH","source","ZAP","evidenceConfidence","ENGINE_CANDIDATE",
                "title","SQL 注入候选 "+n,"description","需要结合请求和响应证据人工复核。","reviewStatus","PENDING_REVIEW","taskStatus","OPEN","evidence","X".repeat(50_000)));
        Map<String,Object> run=new LinkedHashMap<>();run.put("id","qa-run");run.put("status","COMPLETED");
        run.put("configSnapshot",json.writeValueAsString(Map.of("target",Map.of("baseUrl","http://demo:8081/"),"suite",Map.of("versionNo",2,"definition",Map.of("pages",List.of("/"),"apiCases",List.of(),"performance",Map.of("vus",List.of(1,5,10),"p95Ms",1000))),"modules",List.of("FRONTEND","SECURITY","PERFORMANCE"),"activeScan",true)));
        String perf=json.writeValueAsString(Map.of("summary",Map.of("metrics",Map.of("http_req_duration",Map.of("med",21.1,"p(95)",42.3,"p(99)",69.2),"http_reqs",Map.of("rate",5.4),"http_req_failed",Map.of("value",0.02)),"firstExceededStage",3,"timeSeries",List.of(Map.of("windowStartSecond",0,"stage",1,"p50Ms",21.1,"p95Ms",42.3,"p99Ms",69.2,"throughputPerSecond",5.4,"errorRate",0.02)))));
        run.put("modules",List.of(Map.of("moduleType","FRONTEND","status","COMPLETED","summary","页面检测完成"),Map.of("moduleType","SECURITY","status","COMPLETED","summary","发现候选问题"),Map.of("moduleType","PERFORMANCE","status","COMPLETED","summary","P95 超过阈值","resultPayload",perf)));
        run.put("issues",issues);
        String payload=json.writeValueAsString(Map.of("schemaVersion","0.6.1","run",run,"tools",Map.of("security",Map.of("version","0.6.1")),"issueAudit",List.of(Map.of("issueId","issue-1","reviewStatus","CONFIRMED","taskStatus","VERIFIED","retestRunId","run-2","recurrenceOfIssueId","","history",List.of(Map.of("action","REVIEWED","operator","QA","createdAt","2026-09-24","details","人工复核")))),"scopeNotice","仅覆盖已登记目标；未执行项不表示通过。","securityNotice","候选需人工复核。"));
        Path path=System.getProperty("pdf.qa.path")==null?temporary.resolve("quality-report-qa.pdf"):Path.of(System.getProperty("pdf.qa.path"));
        Files.createDirectories(path.getParent());
        var service=new TestRunReportService(null,json,null,null,null);
        service.renderPdf(new TestRunReportService.Snapshot("qa-snapshot","qa-run","0.6.1","sample-hash",payload,LocalDateTime.of(2026,9,23,12,0),true,false),path);
        assertTrue(Files.size(path)>2000);
        assertTrue(Files.readString(path,java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }

    @Test void showsDerivedFailureAndUnavailableLatencyInNewSnapshot() throws Exception {
        ObjectMapper json=new ObjectMapper();
        Map<String,Object> run=new LinkedHashMap<>();
        run.put("id","all-failed-run");run.put("status","COMPLETED");
        run.put("configSnapshot",json.writeValueAsString(Map.of("target",Map.of("baseUrl","http://demo:8081/"),"suite",Map.of("definition",Map.of("performance",Map.of("p95Ms",1000))))));
        String raw=json.writeValueAsString(Map.of("summary",Map.of("metrics",Map.of("http_req_duration",Map.of("med",0,"p(95)",0,"p(99)",0),"http_reqs",Map.of("count",5,"rate",1.2),"http_req_failed",Map.of("value",1)))));
        run.put("modules",List.of(Map.of("moduleType","PERFORMANCE","status","COMPLETED","summary","负载执行已结束","resultPayload",raw,
                "performanceAssessment",Map.of("status","FAILED","reason","所有请求均失败，未取得有效响应耗时","latencyAvailable",false,"allRequestsFailed",true))));
        run.put("issues",List.of());
        String payload=json.writeValueAsString(Map.of("schemaVersion","0.6.1","run",run,"tools",Map.of(),"scopeNotice","仅覆盖已登记目标。","securityNotice","候选需人工复核。"));
        Path path=System.getProperty("pdf.qa.performance.path")==null?temporary.resolve("performance-assessment-qa.pdf"):Path.of(System.getProperty("pdf.qa.performance.path"));
        Files.createDirectories(path.getParent());
        new TestRunReportService(null,json,null,null,null).renderPdf(new TestRunReportService.Snapshot("qa-performance","all-failed-run","0.6.1","sample-hash",payload,LocalDateTime.of(2026,9,28,12,0),true,false),path);
        assertTrue(Files.size(path)>2000);
    }
}
