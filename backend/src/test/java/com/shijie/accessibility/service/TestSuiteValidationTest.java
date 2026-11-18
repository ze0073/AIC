package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestSuiteValidationTest {
    private final TestSuiteService suites=new TestSuiteService(null,null,null,null);

    @Test void acceptsBoundedLoadStages() {
        assertDoesNotThrow(()->suites.validateDefinition(Map.of("performance",Map.of(
                "vus",List.of(1,5,20),"stageDurationSeconds",40,"ratePerSecond",50,
                "p95Ms",1000,"errorRate",0.01))));
    }

    @Test void rejectsOutOfRangeEffectiveK6Settings() {
        assertThrows(ApiException.class,()->suites.validateDefinition(Map.of("performance",Map.of("vus",List.of(21)))));
        assertThrows(ApiException.class,()->suites.validateDefinition(Map.of("performance",Map.of("stageDurationSeconds",41))));
        assertThrows(ApiException.class,()->suites.validateDefinition(Map.of("performance",Map.of("ratePerSecond",51))));
    }
}
