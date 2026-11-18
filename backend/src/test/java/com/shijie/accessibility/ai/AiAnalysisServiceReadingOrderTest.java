package com.shijie.accessibility.ai;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.mapper.EvidenceMapper;
import com.shijie.accessibility.model.ArtifactEntity;
import com.shijie.accessibility.model.EvidenceEntity;
import com.shijie.accessibility.model.FindingEntity;
import com.shijie.accessibility.service.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AiAnalysisServiceReadingOrderTest {
    @TempDir Path temp;

    @Test
    void sendsOnlyFourLocalConflictRegionsAndTwelveElementSummaries() throws Exception {
        StorageService storage = new StorageService(new AppProperties(temp,
                new AppProperties.Scanner(10, 1_000, 10_000), AppProperties.Ai.defaults(false)));
        Path screenshot = temp.resolve("scan.png");
        ImageIO.write(new BufferedImage(1440, 1800, BufferedImage.TYPE_INT_RGB), "png", screenshot.toFile());
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.id = "01AIARTIFACT0000000000000";
        artifact.relativePath = storage.relativeToRoot(screenshot);

        List<Map<String, Object>> elements = new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("selector", "#item-" + index);
            item.put("tag", "button"); item.put("name", "按钮 " + index);
            item.put("outerHtml", "<button id='item-" + index + "'>按钮 " + index + "</button>");
            item.put("domIndex", index); item.put("visualIndex", 14 - index); item.put("order", 0);
            item.put("boundingBox", Map.of("x", 40 + index * 20, "y", 40 + index * 60,
                    "width", 120, "height", 36));
            elements.add(item);
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int index = 0; index < 5; index++) conflicts.add(Map.of(
                "domBefore", "#item-" + index, "visuallyBefore", "#item-" + (index + 1),
                "domIndexA", index, "domIndexB", index + 1, "visualIndexA", 14 - index,
                "visualIndexB", 13 - index));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", "RISK"); evidence.put("writingMode", "horizontal-tb");
        evidence.put("direction", "ltr"); evidence.put("candidateCount", 15);
        evidence.put("inversionCount", 5); evidence.put("inversionRatio", .2);
        evidence.put("domSequence", elements); evidence.put("inversions", conflicts);

        AtomicReference<AiProvider.Request> sent = new AtomicReference<>();
        AiProvider provider = new AiProvider() {
            public boolean enabled() { return true; }
            public String provider() { return "test"; }
            public String model() { return "test-vl"; }
            public String unavailableReason() { return ""; }
            public Optional<Suggestion> suggest(Request request) {
                sent.set(request);
                return Optional.of(new Suggestion("需人工确认", "medium", List.of("顺序冲突"),
                        "请使用读屏软件复核", "不得保留的候选补丁", true));
            }
        };
        EvidenceMapper mapper = mock(EvidenceMapper.class);
        AiAnalysisService service = new AiAnalysisService(provider, mapper, storage, new ObjectMapper());
        FindingEntity finding = new FindingEntity();
        finding.id = "01AIFINDING0000000000000"; finding.ruleId = "R009";
        finding.pagePath = "index.html"; finding.selector = "html";

        assertThat(service.analyze(finding, evidence, artifact).created()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sentElements = (List<Map<String, Object>>) sent.get().evidence().get("elements");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sentConflicts = (List<Map<String, Object>>) sent.get().evidence().get("conflicts");
        assertThat(sentElements).hasSize(12).allSatisfy(item -> assertThat(item).doesNotContainKey("boundingBox"));
        assertThat(sentConflicts).hasSize(4);
        byte[] imageBytes = Base64.getDecoder().decode(sent.get().screenshotDataUrl().split(",", 2)[1]);
        BufferedImage contactSheet = ImageIO.read(new ByteArrayInputStream(imageBytes));
        assertThat(contactSheet.getWidth()).isLessThanOrEqualTo(960);
        assertThat(contactSheet.getHeight()).isLessThanOrEqualTo(640);

        ArgumentCaptor<EvidenceEntity> saved = ArgumentCaptor.forClass(EvidenceEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().payload).contains("\"candidateText\":\"\"")
                .doesNotContain("不得保留的候选补丁");

        AiProvider failingProvider = new AiProvider() {
            public boolean enabled() { return true; }
            public String provider() { return "test"; }
            public String model() { return "test-vl"; }
            public String unavailableReason() { return ""; }
            public Optional<Suggestion> suggest(Request request) { throw new AiProviderException("HTTP 401"); }
        };
        clearInvocations(mapper);
        var failed = new AiAnalysisService(failingProvider, mapper, storage, new ObjectMapper())
                .analyze(finding, evidence, artifact);
        assertThat(failed.created()).isFalse();
        assertThat(failed.failure()).contains("401");
        verifyNoInteractions(mapper);
    }
}
