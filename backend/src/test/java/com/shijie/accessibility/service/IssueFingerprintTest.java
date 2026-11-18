package com.shijie.accessibility.service;

import com.shijie.accessibility.scanner.OnlineFrontendScanner;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IssueFingerprintTest {
    @Test void dynamicEvidenceDoesNotChangeCaseIdentity() {
        var first = new OnlineFrontendScanner.IssueDraft("FLOW_STEP_FAILED", "FLOW", "HIGH", "REPRODUCIBLE_ASSERTION", "失败", "失败",
                Map.of("step", 2, "selector", "#submit", "screenshotBase64", "image-a", "error", "timeout"));
        var second = new OnlineFrontendScanner.IssueDraft("FLOW_STEP_FAILED", "FLOW", "HIGH", "REPRODUCIBLE_ASSERTION", "失败", "失败",
                Map.of("step", 2, "selector", "#submit", "screenshotBase64", "image-b", "error", "hidden"));
        var otherStep = new OnlineFrontendScanner.IssueDraft("FLOW_STEP_FAILED", "FLOW", "HIGH", "REPRODUCIBLE_ASSERTION", "失败", "失败",
                Map.of("step", 3, "selector", "#submit"));
        assertEquals(IssueFingerprint.of("FRONTEND", first), IssueFingerprint.of("FRONTEND", second));
        assertNotEquals(IssueFingerprint.of("FRONTEND", first), IssueFingerprint.of("FRONTEND", otherStep));
    }
}
