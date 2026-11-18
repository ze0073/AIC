package com.shijie.accessibility.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunComparisonServiceTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void comparesOnlyMatchingCompletedModulesAndReportsUnavailableReasons() throws Exception {
        TestRunService runs=mock(TestRunService.class);
        String configuration=json.writeValueAsString(Map.of("suite",Map.of("definition",Map.of("performance",Map.of("vus",List.of(1,5,10),"p95Ms",1000)))));
        String slow=json.writeValueAsString(Map.of("summary",Map.of("metrics",Map.of("http_req_duration",Map.of("med",100,"p(95)",500,"p(99)",900),"http_reqs",Map.of("count",20,"rate",4),"http_req_failed",Map.of("value",0.1)))));
        String fast=json.writeValueAsString(Map.of("summary",Map.of("metrics",Map.of("http_req_duration",Map.of("med",40,"p(95)",120,"p(99)",200),"http_reqs",Map.of("count",20,"rate",10),"http_req_failed",Map.of("value",0.01)))));
        when(runs.get("old")).thenReturn(run("old","target","suite",configuration,"COMPLETED",slow));
        when(runs.get("new")).thenReturn(run("new","target","suite",configuration,"COMPLETED",fast));
        Map<String,Object> comparison=new RunComparisonService(runs,json).compare("new","old");
        assertEquals(true,comparison.get("comparable"));
        @SuppressWarnings("unchecked") Map<String,Object> modules=(Map<String,Object>)comparison.get("modules");
        @SuppressWarnings("unchecked") Map<String,Object> performance=(Map<String,Object>)modules.get("PERFORMANCE");
        assertEquals(true,performance.get("comparable"));
        @SuppressWarnings("unchecked") Map<String,Object> delta=(Map<String,Object>)performance.get("delta");
        assertEquals(-380.0,delta.get("p95Ms"));
        assertEquals(false,((Map<?,?>)modules.get("FRONTEND")).get("comparable"));
        when(runs.get("new")).thenReturn(run("new","target","another-suite",configuration,"COMPLETED",fast));
        assertTrue(((List<?>)new RunComparisonService(runs,json).compare("new","old").get("reasons")).contains("套件版本不同"));
        when(runs.get("new")).thenReturn(run("new","target","suite",configuration,"PARTIAL",fast));
        assertEquals(false,((Map<?,?>)((Map<?,?>)new RunComparisonService(runs,json).compare("new","old").get("modules")).get("PERFORMANCE")).get("comparable"));
    }

    @Test void rejectsComparisonWhenEveryRequestInBaselineFailed() throws Exception {
        TestRunService runs=mock(TestRunService.class);
        String configuration=json.writeValueAsString(Map.of("suite",Map.of("definition",Map.of("performance",Map.of("vus",List.of(1))))));
        String broken=json.writeValueAsString(Map.of("summary",Map.of("metrics",Map.of("http_req_duration",Map.of("med",0,"p(95)",0,"p(99)",0),"http_reqs",Map.of("count",5,"rate",1.2),"http_req_failed",Map.of("value",1)))));
        String fixed=json.writeValueAsString(Map.of("summary",Map.of("metrics",Map.of("http_req_duration",Map.of("med",10,"p(95)",20,"p(99)",25),"http_reqs",Map.of("count",5,"rate",1.2),"http_req_failed",Map.of("value",0)))));
        when(runs.get("old")).thenReturn(run("old","target","suite",configuration,"COMPLETED",broken));
        when(runs.get("new")).thenReturn(run("new","target","suite",configuration,"COMPLETED",fixed));
        Map<?,?> modules=(Map<?,?>)new RunComparisonService(runs,json).compare("new","old").get("modules");
        Map<?,?> performance=(Map<?,?>)modules.get("PERFORMANCE");
        assertEquals(false,performance.get("comparable"));
        assertTrue(((List<?>)performance.get("reasons")).stream().anyMatch(reason->reason.toString().contains("基线运行缺少有效响应耗时")));
    }

    @Test void securityComparisonRejectsDifferentScanAndUploadCoverage() throws Exception {
        TestRunService runs=mock(TestRunService.class);
        String active=json.writeValueAsString(Map.of("activeScan",true,"suite",Map.of("definition",Map.of("performance",Map.of()))));
        String passive=json.writeValueAsString(Map.of("activeScan",false,"schedule","DAILY_REGRESSION","uploadEnabled",false,"suite",Map.of("definition",Map.of("performance",Map.of()))));
        when(runs.get("old")).thenReturn(securityRun("old",active));
        when(runs.get("new")).thenReturn(securityRun("new",passive));
        @SuppressWarnings("unchecked") Map<String,Object> modules=(Map<String,Object>)new RunComparisonService(runs,json).compare("new","old").get("modules");
        @SuppressWarnings("unchecked") List<String> reasons=(List<String>)((Map<String,Object>)modules.get("SECURITY")).get("reasons");
        assertTrue(reasons.contains("主动扫描开关不同"));
        assertTrue(reasons.contains("上传检查开关不同"));
    }

    private TestRunService.RunView securityRun(String id,String configuration){
        return new TestRunService.RunView(id,"project","target","suite","COMPLETED",false,configuration,"{}",null,LocalDateTime.now(),null,null,
                List.of(new TestRunService.ModuleView("module-"+id,"SECURITY","COMPLETED",null,"{}",null,null)),List.of());
    }

    private TestRunService.RunView run(String id,String target,String suite,String configuration,String status,String payload){
        return new TestRunService.RunView(id,"project",target,suite,status,false,configuration,"{}",null,LocalDateTime.now(),null,null,
                List.of(new TestRunService.ModuleView("module-"+id,"PERFORMANCE",status,null,payload,null,null)),List.of());
    }
}
