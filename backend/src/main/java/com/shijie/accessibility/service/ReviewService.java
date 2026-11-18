package com.shijie.accessibility.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.mapper.FindingMapper;
import com.shijie.accessibility.mapper.FindingReviewMapper;
import com.shijie.accessibility.mapper.ReviewPolicyMapper;
import com.shijie.accessibility.model.FindingEntity;
import com.shijie.accessibility.model.FindingReviewEntity;
import com.shijie.accessibility.model.ReviewPolicyEntity;
import com.shijie.accessibility.model.ScanTaskEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
public class ReviewService {
    private static final Set<String> ACTIONS = Set.of(
            "CONFIRM_RISK", "DISMISS_FALSE_POSITIVE", "NEEDS_FOLLOW_UP");

    private final FindingMapper findings;
    private final FindingReviewMapper reviews;
    private final ReviewPolicyMapper policies;
    private final ScanService scans;

    public ReviewService(FindingMapper findings, FindingReviewMapper reviews,
                         ReviewPolicyMapper policies, ScanService scans) {
        this.findings = findings;
        this.reviews = reviews;
        this.policies = policies;
        this.scans = scans;
    }

    @Transactional
    public ReviewResult review(String findingId, ReviewRequest request) {
        FindingEntity finding = requiredFinding(findingId);
        if (!"R009".equals(finding.ruleId)) {
            throw ApiException.badRequest("REVIEW_NOT_SUPPORTED", "当前人工风险复核仅适用于 R009 候选");
        }
        String action = request == null || request.action() == null ? "" : request.action().trim().toUpperCase();
        if (!ACTIONS.contains(action)) {
            throw ApiException.badRequest("INVALID_REVIEW_ACTION", "复核操作不受支持");
        }
        String comment = request.comment() == null ? "" : request.comment().trim();
        if (comment.length() > 1000) {
            throw ApiException.badRequest("COMMENT_TOO_LONG", "复核说明不能超过 1000 字");
        }
        if ("DISMISS_FALSE_POSITIVE".equals(action) && comment.length() < 5) {
            throw ApiException.badRequest("COMMENT_REQUIRED", "标记误报时需要填写 5–1000 字理由");
        }

        FindingReviewEntity review = new FindingReviewEntity();
        review.id = Ulids.next();
        review.findingId = finding.id;
        review.action = action;
        review.comment = comment.isBlank() ? null : comment;
        review.reused = false;
        review.createdAt = LocalDateTime.now();
        reviews.insert(review);

        finding.status = switch (action) {
            case "CONFIRM_RISK" -> "CONFIRMED";
            case "DISMISS_FALSE_POSITIVE" -> "DISMISSED";
            default -> "NEEDS_REVIEW";
        };
        findings.updateById(finding);

        ScanTaskEntity scan = scans.get(finding.scanTaskId);
        String normalizedSelector = ScanService.normalizeSelector(finding.selector);
        String matchKey = ScanService.reviewMatchKey(scan.projectId, finding.ruleId, finding.pagePath,
                normalizedSelector, finding.evidenceFingerprint);
        ReviewPolicyEntity policy = policies.selectOne(new QueryWrapper<ReviewPolicyEntity>()
                .eq("project_id", scan.projectId).eq("match_key", matchKey).last("LIMIT 1"));
        if ("NEEDS_FOLLOW_UP".equals(action)) {
            if (policy != null) {
                policy.active = false;
                policy.updatedAt = LocalDateTime.now();
                policies.updateById(policy);
            }
        } else {
            if (policy == null) {
                policy = new ReviewPolicyEntity();
                policy.id = Ulids.next();
                policy.projectId = scan.projectId;
                policy.matchKey = matchKey;
                policy.ruleId = finding.ruleId;
                policy.pagePath = finding.pagePath;
                policy.selector = normalizedSelector;
                policy.evidenceFingerprint = finding.evidenceFingerprint;
                policy.createdAt = LocalDateTime.now();
            }
            policy.decision = action;
            policy.comment = review.comment;
            policy.sourceReviewId = review.id;
            policy.active = true;
            policy.updatedAt = LocalDateTime.now();
            if (policies.selectById(policy.id) == null) policies.insert(policy); else policies.updateById(policy);
        }
        return new ReviewResult(finding, review, scans.recountById(finding.scanTaskId));
    }

    public ReviewResult resultByReview(String reviewId) {
        FindingReviewEntity review = reviews.selectById(reviewId);
        if (review == null) throw ApiException.notFound("复核记录不存在");
        FindingEntity finding = requiredFinding(review.findingId);
        return new ReviewResult(finding, review, scans.get(finding.scanTaskId));
    }

    private FindingEntity requiredFinding(String id) {
        FindingEntity finding = findings.selectById(id);
        if (finding == null) throw ApiException.notFound("问题不存在");
        return finding;
    }

    public record ReviewRequest(String action, String comment) {}
    public record ReviewResult(FindingEntity finding, FindingReviewEntity review, ScanTaskEntity scan) {}
}
