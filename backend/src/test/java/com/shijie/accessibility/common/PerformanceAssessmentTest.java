package com.shijie.accessibility.common;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceAssessmentTest {
    private static Map<String,Object> summary(double errorRate, double p95, Object threshold) {
        return Map.of("metrics",Map.of(
                "http_reqs",Map.of("count",5,"rate",1.2),
                "http_req_failed",Map.of("value",errorRate,"thresholds",Map.of("rate<0.01",threshold)),
                "http_req_duration",Map.of("med",p95/2,"p(95)",p95,"p(99)",p95)));
    }

    @Test void allFailedHistoricalRunHasNoValidLatencyAndFailsDespiteZeroDurations() {
        var result=PerformanceAssessment.evaluate(summary(1,0,true),Map.of("p95Ms",1000,"errorRate",0.01),"COMPLETED");
        assertEquals("FAILED",result.status());
        assertTrue(result.allRequestsFailed());
        assertFalse(result.latencyAvailable());
        assertNull(result.p95Ms());
        assertTrue(result.thresholdsFailed());
    }

    @Test void modernThresholdFailureAndRealSamplesFailTheAcceptanceCheck() {
        var result=PerformanceAssessment.evaluate(summary(0,40,Map.of("ok",false)),Map.of("p95Ms",1000),"COMPLETED");
        assertTrue(result.latencyAvailable());
        assertEquals("FAILED",result.status());
    }

    @Test void successfulResponseWithRealMetricsPassesAndMissingMetricsStayInconclusive() {
        assertEquals("PASSED",PerformanceAssessment.evaluate(summary(0,40,false),Map.of(),"COMPLETED").status());
        assertEquals("INCONCLUSIVE",PerformanceAssessment.evaluate(Map.of("metrics",Map.of("http_reqs",Map.of("count",5))),Map.of(),"COMPLETED").status());
    }

    @Test void cancelledRunNeverPassesEvenWhenPartialSamplesExist() {
        assertEquals("INCONCLUSIVE",PerformanceAssessment.evaluate(summary(0,40,false),Map.of(),"CANCELLED").status());
        assertEquals("INCONCLUSIVE",PerformanceAssessment.evaluate(summary(1,0,true),Map.of(),"CANCELLED").status());
    }
}
