package com.shijie.accessibility.service;

import com.shijie.accessibility.ai.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IssueExplanationServiceTest {
    @Test void quickRuleExplanationChecksAccessAndNeverCallsTheModel(){
        CollaborationService collaboration=mock(CollaborationService.class);
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        AiProvider provider=mock(AiProvider.class);
        when(collaboration.issue("issue")).thenReturn(Map.of("projectId","project","source","ZAP","title","权限候选","description","保存的响应中存在其他身份的数据"));
        var explanation=new IssueExplanationService(collaboration,jdbc,new ObjectMapper(),provider).explain("issue",false);
        assertEquals("RULE",explanation.mode());
        assertTrue(explanation.evidenceBasis().getFirst().contains("保存的响应"));
        assertFalse(explanation.verificationSteps().isEmpty());
        verify(collaboration).issue("issue");
        verifyNoInteractions(provider,jdbc);
    }

    @Test void failedAiReturnsSavedEvidenceAndActionableRuleSteps(){
        CollaborationService collaboration=mock(CollaborationService.class);
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        AiProvider provider=mock(AiProvider.class);
        when(collaboration.issue("issue")).thenReturn(Map.of("projectId","project","source","ZAP","title","权限候选","description","保存的响应证据"));
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),eq("project"))).thenReturn(java.util.List.of());
        when(provider.enabled()).thenReturn(true);
        when(provider.explainIssue(any())).thenThrow(new com.shijie.accessibility.ai.AiProviderException("在线解释等待超时"));
        var explanation=new IssueExplanationService(collaboration,jdbc,new ObjectMapper(),provider).explain("issue");
        assertEquals("RULE",explanation.mode());
        assertTrue(explanation.unavailableReason().contains("超时"));
        assertEquals("保存的响应证据",explanation.evidenceBasis().getFirst());
        assertFalse(explanation.fixSuggestions().isEmpty());
    }

    @Test void unavailableAiIsExplicitlyLabeledRuleExplanationAndRequiresHumanReview(){
        CollaborationService collaboration=mock(CollaborationService.class);
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        AiProvider provider=mock(AiProvider.class);
        when(collaboration.issue("issue")).thenReturn(Map.of("projectId","project","source","ZAP","title","候选问题",
                "description","需要人工复现","evidence",Map.of("url","/api/test")));
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),eq("project"))).thenReturn(java.util.List.of());
        when(provider.enabled()).thenReturn(false);
        when(provider.unavailableReason()).thenReturn("未配置 Qwen");
        var explanation=new IssueExplanationService(collaboration,jdbc,new ObjectMapper(),provider).explain("issue");
        assertEquals("RULE",explanation.mode());
        assertEquals("未配置 Qwen",explanation.unavailableReason());
        assertTrue(explanation.reviewRequired());
        assertNull(explanation.provider());
        verify(provider,never()).explainIssue(any());
    }
}
