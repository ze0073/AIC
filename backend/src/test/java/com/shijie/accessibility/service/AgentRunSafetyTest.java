package com.shijie.accessibility.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunSafetyTest {
    @Test void onlyReadOnlyNonUploadApiCasesReachAgentSecurityRunner() {
        List<Map<String,Object>> cases=List.of(
                Map.of("method","GET","kind","ROLE_AUTHORIZATION","name","read"),
                Map.of("method","POST","kind","ROLE_AUTHORIZATION","name","write"),
                Map.of("method","GET","kind","UPLOAD_SECURITY","name","upload"),
                Map.of("method","GET","body","{\"update\":true}","name","get-with-body"),
                Map.of("method","HEAD","kind","ASSERTION","name","head"));
        assertThat(TestRunService.safeApiCases(cases)).extracting(item->item.get("name"))
                .containsExactly("read","head");
    }
}
