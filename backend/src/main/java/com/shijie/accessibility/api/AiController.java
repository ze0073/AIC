package com.shijie.accessibility.api;

import com.shijie.accessibility.ai.AiAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes capability only; credentials and provider URLs are never returned. */
@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiAnalysisService analysis;

    public AiController(AiAnalysisService analysis) { this.analysis = analysis; }

    @GetMapping("/status")
    Status status() {
        boolean available = analysis.available();
        return new Status(available, analysis.provider(), available ? analysis.model() : null,
                available ? null : analysis.unavailableReason());
    }

    public record Status(boolean available, String provider, String model, String unavailableReason) {}
}
