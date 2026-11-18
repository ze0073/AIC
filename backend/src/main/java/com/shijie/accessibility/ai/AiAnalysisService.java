package com.shijie.accessibility.ai;

import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.mapper.EvidenceMapper;
import com.shijie.accessibility.model.ArtifactEntity;
import com.shijie.accessibility.model.EvidenceEntity;
import com.shijie.accessibility.model.FindingEntity;
import com.shijie.accessibility.service.StorageService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/** Persists advisory-only AI output as a separate evidence record. */
@Service
public class AiAnalysisService {
    private static final Set<String> SUPPORTED_RULES = Set.of("R001", "R002", "R003", "R009");
    private static final int CROP_PADDING = 96;
    private static final int MAX_EDGE = 1024;

    private final AiProvider provider;
    private final EvidenceMapper evidences;
    private final StorageService storage;
    private final ObjectMapper json;

    public AiAnalysisService(AiProvider provider, EvidenceMapper evidences, StorageService storage, ObjectMapper json) {
        this.provider = provider;
        this.evidences = evidences;
        this.storage = storage;
        this.json = json;
    }

    public boolean available() { return provider.enabled(); }
    public String unavailableReason() { return provider.unavailableReason(); }
    public String provider() { return provider.provider(); }
    public String model() { return provider.model(); }
    public boolean supports(String ruleId) { return SUPPORTED_RULES.contains(ruleId); }

    /**
     * Any exception is converted to a result for ScanService to log. AI must never fail P0 scanning.
     */
    public AnalysisResult analyze(FindingEntity finding, Map<String, Object> rawEvidence, ArtifactEntity screenshot) {
        if (!supports(finding.ruleId)) return AnalysisResult.skipped();
        if (!provider.enabled()) return AnalysisResult.unavailable(provider.unavailableReason());
        try {
            Optional<AiProvider.Suggestion> suggested = provider.suggest(new AiProvider.Request(
                    finding.ruleId, finding.pagePath, finding.selector, minimalEvidence(finding.ruleId, rawEvidence),
                    analysisImage(finding.ruleId, rawEvidence, screenshot)));
            if (suggested.isEmpty()) return AnalysisResult.unavailable(provider.unavailableReason());
            save(finding, screenshot, suggested.get());
            return AnalysisResult.success();
        } catch (Exception ex) {
            return AnalysisResult.failed(concise(ex));
        }
    }

    private void save(FindingEntity finding, ArtifactEntity screenshot, AiProvider.Suggestion suggestion) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", provider.provider());
        payload.put("model", provider.model());
        payload.put("confidenceBand", suggestion.confidenceBand());
        payload.put("reasons", suggestion.reasons());
        payload.put("recommendedAction", suggestion.recommendedAction());
        payload.put("candidateText", "R009".equals(finding.ruleId) ? "" : suggestion.candidateText());
        payload.put("reviewRequired", true);

        EvidenceEntity evidence = new EvidenceEntity();
        evidence.id = Ulids.next();
        evidence.findingId = finding.id;
        evidence.kind = "AI";
        evidence.summary = truncate(suggestion.summary(), 500);
        evidence.payload = json.writeValueAsString(payload);
        evidence.artifactId = screenshot.id;
        evidence.createdAt = LocalDateTime.now();
        evidences.insert(evidence);
    }

    private Map<String, Object> minimalEvidence(String ruleId, Map<String, Object> raw) {
        Map<String, Object> minimal = new LinkedHashMap<>();
        if (raw == null) return minimal;
        if ("R009".equals(ruleId)) {
            for (String key : List.of("status", "writingMode", "direction", "candidateCount",
                    "truncated", "comparablePairs", "inversionCount", "inversionRatio")) {
                if (raw.containsKey(key)) minimal.put(key, raw.get(key));
            }
            minimal.put("elements", summarizedElements(raw));
            minimal.put("conflicts", limitedMaps(raw.get("inversions"), 4, Set.of(
                    "domBefore", "visuallyBefore", "domIndexA", "domIndexB", "visualIndexA", "visualIndexB")));
            minimal.put("explicitOrder", limitedMaps(raw.get("explicitOrder"), 12, Set.of("selector", "order")));
            return minimal;
        }
        copy(raw, minimal, "outerHtml", 2_000);
        copy(raw, minimal, "description", 600);
        copy(raw, minimal, "details", 1_200);
        Object bounding = raw.get("boundingBox");
        if (bounding != null) minimal.put("boundingBox", bounding);
        return minimal;
    }

    private List<Map<String, Object>> summarizedElements(Map<String, Object> raw) {
        return limitedMaps(raw.get("domSequence"), 12, Set.of(
                "selector", "tag", "role", "name", "outerHtml", "domIndex", "visualIndex", "order"));
    }

    private List<Map<String, Object>> limitedMaps(Object value, int limit, Set<String> keys) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> output = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || output.size() >= limit) continue;
            Map<String, Object> selected = new LinkedHashMap<>();
            for (String key : keys) {
                Object raw = map.get(key);
                if (raw instanceof String text) selected.put(key, truncate(text, "outerHtml".equals(key) ? 500 : 160));
                else if (raw != null) selected.put(key, raw);
            }
            output.add(selected);
        }
        return output;
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String key, int max) {
        Object value = source.get(key);
        if (value instanceof String text) target.put(key, truncate(text, max));
        else if (value != null) target.put(key, value);
    }

    private String analysisImage(String ruleId, Map<String, Object> evidence, ArtifactEntity artifact) throws Exception {
        return "R009".equals(ruleId) ? readingOrderContactSheet(evidence, artifact)
                : croppedScreenshot(evidence, artifact);
    }

    private String croppedScreenshot(Map<String, Object> evidence, ArtifactEntity artifact) throws Exception {
        Path path = storage.resolveArtifact(artifact.relativePath);
        BufferedImage source = ImageIO.read(path.toFile());
        if (source == null) throw new AiProviderException("无法读取 AI 分析所需截图");
        Map<?, ?> box = evidence != null && evidence.get("boundingBox") instanceof Map<?, ?> map ? map : Map.of();
        int x = Math.max(0, number(box.get("x"), 0) - CROP_PADDING);
        int y = Math.max(0, number(box.get("y"), 0) - CROP_PADDING);
        int right = Math.min(source.getWidth(), number(box.get("x"), 0) + number(box.get("width"), source.getWidth()) + CROP_PADDING);
        int bottom = Math.min(source.getHeight(), number(box.get("y"), 0) + number(box.get("height"), source.getHeight()) + CROP_PADDING);
        if (right <= x || bottom <= y) { x = 0; y = 0; right = source.getWidth(); bottom = source.getHeight(); }
        BufferedImage cropped = source.getSubimage(x, y, right - x, bottom - y);
        BufferedImage resized = resize(cropped);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(resized, "png", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
    }

    private String readingOrderContactSheet(Map<String, Object> evidence, ArtifactEntity artifact) throws Exception {
        BufferedImage source = ImageIO.read(storage.resolveArtifact(artifact.relativePath).toFile());
        if (source == null) throw new AiProviderException("无法读取阅读顺序分析截图");
        Map<String, Map<?, ?>> elements = new LinkedHashMap<>();
        if (evidence != null && evidence.get("domSequence") instanceof List<?> sequence) {
            for (Object item : sequence) {
                if (item instanceof Map<?, ?> map && map.get("selector") != null) {
                    elements.put(String.valueOf(map.get("selector")), map);
                }
            }
        }
        List<List<String>> groups = new ArrayList<>();
        if (evidence != null && evidence.get("inversions") instanceof List<?> inversions) {
            for (Object item : inversions) {
                if (!(item instanceof Map<?, ?> map) || groups.size() >= 4) continue;
                groups.add(List.of(String.valueOf(map.get("domBefore")), String.valueOf(map.get("visuallyBefore"))));
            }
        }
        if (groups.isEmpty() && evidence != null && evidence.get("explicitOrder") instanceof List<?> ordered) {
            for (Object item : ordered) {
                if (item instanceof Map<?, ?> map && groups.size() < 4 && map.get("selector") != null)
                    groups.add(List.of(String.valueOf(map.get("selector"))));
            }
        }
        if (groups.isEmpty()) throw new AiProviderException("阅读顺序候选缺少可发送的局部冲突区域");

        int tileWidth = 480, tileHeight = 320, columns = Math.min(2, groups.size());
        int rows = (groups.size() + columns - 1) / columns;
        BufferedImage sheet = new BufferedImage(tileWidth * columns, tileHeight * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = sheet.createGraphics();
        try {
            canvas.setColor(Color.WHITE); canvas.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
                List<String> selectors = groups.get(groupIndex);
                java.awt.Rectangle region = unionRegion(selectors, elements, source);
                BufferedImage crop = source.getSubimage(region.x, region.y, region.width, region.height);
                double scale = Math.min((tileWidth - 16d) / crop.getWidth(), (tileHeight - 42d) / crop.getHeight());
                int drawWidth = Math.max(1, (int) Math.round(crop.getWidth() * scale));
                int drawHeight = Math.max(1, (int) Math.round(crop.getHeight() * scale));
                int offsetX = (groupIndex % columns) * tileWidth + 8;
                int offsetY = (groupIndex / columns) * tileHeight + 34;
                canvas.drawImage(crop, offsetX, offsetY, drawWidth, drawHeight, null);
                canvas.setColor(new Color(20, 74, 145));
                canvas.drawString("冲突区域 " + (groupIndex + 1), offsetX, offsetY - 10);
                for (int selectorIndex = 0; selectorIndex < selectors.size(); selectorIndex++) {
                    Map<?, ?> item = elements.get(selectors.get(selectorIndex));
                    if (item == null || !(item.get("boundingBox") instanceof Map<?, ?> box)) continue;
                    int x = offsetX + (int) Math.round((number(box.get("x"), 0) - region.x) * scale);
                    int y = offsetY + (int) Math.round((number(box.get("y"), 0) - region.y) * scale);
                    canvas.setColor(selectorIndex == 0 ? new Color(190, 40, 40) : new Color(30, 120, 65));
                    canvas.fillOval(x - 3, y - 17, 24, 24);
                    canvas.setColor(Color.WHITE);
                    canvas.drawString(String.valueOf(selectorIndex + 1), x + 3, y + 2);
                }
            }
        } finally {
            canvas.dispose();
        }
        return dataUrl(resize(sheet));
    }

    private java.awt.Rectangle unionRegion(List<String> selectors, Map<String, Map<?, ?>> elements,
                                           BufferedImage source) {
        int left = source.getWidth(), top = source.getHeight(), right = 0, bottom = 0;
        for (String selector : selectors) {
            Map<?, ?> item = elements.get(selector);
            if (item == null || !(item.get("boundingBox") instanceof Map<?, ?> box)) continue;
            int x = number(box.get("x"), 0), y = number(box.get("y"), 0);
            int width = number(box.get("width"), 1), height = number(box.get("height"), 1);
            left = Math.min(left, x); top = Math.min(top, y);
            right = Math.max(right, x + width); bottom = Math.max(bottom, y + height);
        }
        if (right <= left || bottom <= top) throw new AiProviderException("阅读顺序冲突区域缺少坐标");
        left = Math.max(0, left - 64); top = Math.max(0, top - 64);
        right = Math.min(source.getWidth(), right + 64); bottom = Math.min(source.getHeight(), bottom + 64);
        return new java.awt.Rectangle(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }

    private String dataUrl(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
    }

    private BufferedImage resize(BufferedImage source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= MAX_EDGE) return source;
        double scale = (double) MAX_EDGE / longest;
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private int number(Object value, int fallback) {
        if (value instanceof Number number) return Math.max(0, (int) Math.round(number.doubleValue()));
        try { return Math.max(0, (int) Math.round(Double.parseDouble(String.valueOf(value)))); }
        catch (Exception ignored) { return fallback; }
    }

    private String concise(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName()
                : truncate(message.lines().findFirst().orElse(message), 300);
    }

    private String truncate(String value, int max) {
        String text = value == null ? "" : value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public record AnalysisResult(boolean attempted, boolean created, String failure) {
        static AnalysisResult skipped() { return new AnalysisResult(false, false, null); }
        static AnalysisResult unavailable(String reason) { return new AnalysisResult(false, false, reason); }
        static AnalysisResult success() { return new AnalysisResult(true, true, null); }
        static AnalysisResult failed(String reason) { return new AnalysisResult(true, false, reason); }
    }
}
