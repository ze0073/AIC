package com.shijie.accessibility.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Path storageRoot, Scanner scanner, Ai ai) {
    public record Scanner(int maxPages, long pageTimeoutMs, long totalTimeoutMs) {}

    public record Ai(boolean enabled, String provider, String baseUrl, String apiKey,
                     String model, long timeoutMs, int maxRetries) {
        public static Ai defaults(boolean enabled) {
            return new Ai(enabled, "qwen", "", "", "qwen3-vl-plus", 20_000, 1);
        }
    }
}
