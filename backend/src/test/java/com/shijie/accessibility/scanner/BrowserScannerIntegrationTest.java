package com.shijie.accessibility.scanner;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.model.ProjectVersionEntity;
import com.shijie.accessibility.service.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "playwright.tests", matches = "true")
class BrowserScannerIntegrationTest {
    @TempDir Path temp;

    @Test
    void rendersInRestrictedOriginAndProducesAllP0RuleFamilies() throws Exception {
        AtomicInteger externalRequests = new AtomicInteger();
        HttpServer loopback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        loopback.createContext("/blocked.png", exchange -> {
            externalRequests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        loopback.start();
        try {
        var properties = new AppProperties(temp, new AppProperties.Scanner(10, 10_000, 60_000),
                AppProperties.Ai.defaults(false));
        var storage = new StorageService(properties);
        var version = new ProjectVersionEntity();
        version.id = "0000000000000000000000000B";
        version.projectId = "0000000000000000000000000A";
        version.entryPage = "index.html";
        Path source = storage.versionSource(version.projectId, version.id);
        Files.createDirectories(source);
        Files.writeString(source.resolve("index.html"), """
                <!doctype html><html><head><meta charset="utf-8"><style>
                body{font-family:Arial}.low{color:#bbb;background:#fff}.focusless:focus{outline:none}
                </style></head><body>
                <img style="width:20px;height:20px" src="http://127.0.0.1:%d/blocked.png">
                <input style="display:block;width:120px;height:30px">
                <button style="display:block;width:40px;height:30px"></button>
                <h3>跳级标题</h3><p class="low">低对比度文字</p>
                <div class="focusless" onclick="void 0" style="width:80px;height:30px">点击操作</div>
                <i id="duplicate"></i><b id="duplicate"></b>
                <span aria-describedby="missing-description">引用异常</span>
                </body></html>
                """.formatted(loopback.getAddress().getPort()), StandardCharsets.UTF_8);
        BrowserScanner scanner = new BrowserScanner(storage, properties);
        var output = scanner.scan(version, java.util.List.of("index.html"),
                "0000000000000000000000000C", () -> false);
        assertThat(output.pageErrors()).isEmpty();
        assertThat(output.pages()).hasSize(1);
        Set<String> rules = output.pages().get(0).findings().stream()
                .map(BrowserScanner.CollectedFinding::ruleId).collect(Collectors.toSet());
        assertThat(rules).contains("R001", "R002", "R003", "R004", "R005", "R006", "R007", "R008");
        Map<String, Object> focusTrace = output.pages().get(0).focusTrace();
        assertThat(focusTrace.get("maxSteps")).isEqualTo(30);
        @SuppressWarnings("unchecked")
        var forward = (java.util.List<Map<String, Object>>) focusTrace.get("forwardSteps");
        @SuppressWarnings("unchecked")
        var reverse = (java.util.List<Map<String, Object>>) focusTrace.get("reverseSteps");
        assertThat(forward).isNotEmpty().hasSizeLessThanOrEqualTo(30);
        assertThat(reverse).isNotEmpty().hasSizeLessThanOrEqualTo(30);
        assertThat(forward.get(0)).containsKeys("selector", "accessibleName", "tabindex", "boundingBox", "focusIndicator");
        assertThat(output.pages().get(0).screenshot()).exists();
        assertThat(externalRequests).as("loopback requests must be blocked before leaving the browser context").hasValue(0);
        } finally {
            loopback.stop(0);
        }
    }
}
