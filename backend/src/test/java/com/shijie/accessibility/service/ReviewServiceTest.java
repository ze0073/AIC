package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.mapper.FindingMapper;
import com.shijie.accessibility.mapper.FindingReviewMapper;
import com.shijie.accessibility.mapper.ReviewPolicyMapper;
import com.shijie.accessibility.model.FindingEntity;
import com.shijie.accessibility.model.FindingReviewEntity;
import com.shijie.accessibility.model.ReviewPolicyEntity;
import com.shijie.accessibility.model.ScanTaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReviewServiceTest {
    private FindingMapper findings;
    private FindingReviewMapper reviews;
    private ReviewPolicyMapper policies;
    private ScanService scans;
    private ReviewService service;
    private FindingEntity finding;
    private ScanTaskEntity scan;

    @BeforeEach
    void setUp() {
        findings = mock(FindingMapper.class);
        reviews = mock(FindingReviewMapper.class);
        policies = mock(ReviewPolicyMapper.class);
        scans = mock(ScanService.class);
        service = new ReviewService(findings, reviews, policies, scans);

        finding = new FindingEntity();
        finding.id = "01REVIEWFINDING00000000000";
        finding.scanTaskId = "01REVIEWSCAN0000000000000";
        finding.ruleId = "R009";
        finding.pagePath = "index.html";
        finding.selector = "main > button:nth-of-type(2)";
        finding.evidenceFingerprint = "a".repeat(64);
        finding.status = "NEEDS_REVIEW";
        when(findings.selectById(finding.id)).thenReturn(finding);

        scan = new ScanTaskEntity();
        scan.id = finding.scanTaskId;
        scan.projectId = "01REVIEWPROJECT00000000000";
        when(scans.get(scan.id)).thenReturn(scan);
        when(scans.recountById(scan.id)).thenReturn(scan);
    }

    @Test
    void requiresReasonWhenDismissingFalsePositive() {
        assertThatThrownBy(() -> service.review(finding.id,
                new ReviewService.ReviewRequest("DISMISS_FALSE_POSITIVE", "短")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMENT_REQUIRED"));
        verifyNoInteractions(reviews, policies);
    }

    @Test
    void confirmationCreatesReusableExactEvidencePolicyAndUpdatesStatistics() {
        ReviewService.ReviewResult result = service.review(finding.id,
                new ReviewService.ReviewRequest("CONFIRM_RISK", "阅读顺序会改变表单理解"));

        assertThat(result.finding().status).isEqualTo("CONFIRMED");
        assertThat(result.review().reused).isFalse();
        ArgumentCaptor<ReviewPolicyEntity> policy = ArgumentCaptor.forClass(ReviewPolicyEntity.class);
        verify(policies).insert(policy.capture());
        assertThat(policy.getValue().decision).isEqualTo("CONFIRM_RISK");
        assertThat(policy.getValue().projectId).isEqualTo(scan.projectId);
        assertThat(policy.getValue().selector).isEqualTo("main > button:nth-of-type(*)");
        assertThat(policy.getValue().evidenceFingerprint).isEqualTo(finding.evidenceFingerprint);
        assertThat(policy.getValue().matchKey).hasSize(64);
        verify(scans).recountById(scan.id);
    }

    @Test
    void followUpOverridesAndDeactivatesOldReusableDecision() {
        ReviewPolicyEntity old = new ReviewPolicyEntity();
        old.id = "01REVIEWPOLICY000000000000";
        old.active = true;
        when(policies.selectOne(any())).thenReturn(old);

        service.review(finding.id, new ReviewService.ReviewRequest("NEEDS_FOLLOW_UP", "等待设计确认"));

        assertThat(finding.status).isEqualTo("NEEDS_REVIEW");
        assertThat(old.active).isFalse();
        verify(policies).updateById(old);
        verify(policies, never()).insert(any(ReviewPolicyEntity.class));
    }
}
