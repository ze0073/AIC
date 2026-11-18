package com.shijie.accessibility.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestRunStatusTest {
    @Test void retainsPartialOutcomeEvenWhenItWasTheOnlySelectedModule(){
        assertEquals("PARTIAL",TestRunService.aggregateStatus(List.of("PARTIAL"),false));
        assertEquals("PARTIAL",TestRunService.aggregateStatus(List.of("COMPLETED","FAILED"),false));
        assertEquals("FAILED",TestRunService.aggregateStatus(List.of("FAILED"),false));
        assertEquals("CANCELLED",TestRunService.aggregateStatus(List.of("PARTIAL","CANCELLED"),false));
    }
    @Test void changedDetectionCoverageCannotBeCalledARecurrence(){
        Map<String,Object> full=Map.of("activeScan",true);
        Map<String,Object> daily=Map.of("activeScan",false,"schedule","DAILY_REGRESSION","uploadEnabled",false);
        assertEquals(false,TestRunService.sameRecurrenceScope(List.of("SECURITY"),List.of("FRONTEND","SECURITY"),full,full));
        assertEquals(false,TestRunService.sameRecurrenceScope(List.of("SECURITY"),List.of("SECURITY"),full,daily));
        assertEquals(true,TestRunService.sameRecurrenceScope(List.of("FRONTEND","SECURITY"),List.of("SECURITY","FRONTEND"),full,full));
    }
}
