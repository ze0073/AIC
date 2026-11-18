package com.shijie.accessibility.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Hashes;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.mapper.*;
import com.shijie.accessibility.model.*;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FixService {
    private static final String FOCUS_STYLE = """

            /* 视界无障碍：键盘焦点可见性修复 */
            <style data-accessibility-fix="focus-visible">
            :focus-visible { outline: 3px solid #0b5fff !important; outline-offset: 2px !important; }
            </style>
            """;

    private final FixPatchMapper patches;
    private final FindingMapper findings;
    private final ScanTaskMapper scans;
    private final ProjectVersionMapper versions;
    private final ProjectMapper projects;
    private final EvidenceMapper evidences;
    private final StorageService storage;
    private final ScanService scanService;
    private final SourcePatchService sourcePatches;
    private final ObjectMapper json;

    public FixService(FixPatchMapper patches, FindingMapper findings, ScanTaskMapper scans,
                      ProjectVersionMapper versions, ProjectMapper projects, EvidenceMapper evidences,
                      StorageService storage, ScanService scanService, SourcePatchService sourcePatches,
                      ObjectMapper json) {
        this.patches = patches; this.findings = findings; this.scans = scans; this.versions = versions;
        this.projects = projects; this.evidences = evidences; this.storage = storage;
        this.scanService = scanService; this.sourcePatches = sourcePatches; this.json = json;
    }

    public FixPatchEntity get(String id) {
        FixPatchEntity patch = patches.selectById(id);
        if (patch == null) throw ApiException.notFound("修复补丁不存在");
        return patch;
    }

    public FixPatchEntity generate(String findingId, PatchRequest request) throws IOException {
        FindingEntity finding = findings.selectById(findingId);
        if (finding == null) throw ApiException.notFound("问题不存在");
        PatchRequest safeRequest = request == null ? new PatchRequest("zh-CN", null, null, false) : request;
        if ("TEMPLATE_PATCH".equals(finding.fixMode)) return generateTemplate(finding, safeRequest.language());
        if ("SUGGESTION".equals(finding.fixMode) && Set.of("R001", "R003").contains(finding.ruleId)) {
            return generateAiCandidate(finding, safeRequest);
        }
        throw ApiException.badRequest("PATCH_NOT_SUPPORTED", "该问题只提供人工修复建议");
    }

    private FixPatchEntity generateTemplate(FindingEntity finding, String language) throws IOException {
        FixPatchEntity existing = pending(finding.id);
        if (existing != null) return existing;
        ScanTaskEntity scan = scans.selectById(finding.scanTaskId);
        ProjectVersionEntity version = versions.selectById(scan.versionId);
        Path source = storage.resolveVersionFile(version.projectId, version.id, finding.pagePath);
        String before = Files.readString(source, StandardCharsets.UTF_8);
        String after = switch (finding.ruleId) {
            case "R004" -> patchLanguage(before, language);
            case "R002" -> patchLabel(before, finding.selector);
            case "R007" -> patchFocus(before);
            default -> throw ApiException.badRequest("PATCH_NOT_SUPPORTED", "该规则没有模板补丁");
        };
        return savePatch(finding, version, source, before, after, "TEMPLATE", null, false,
                null, null, null);
    }

    private FixPatchEntity generateAiCandidate(FindingEntity finding, PatchRequest request) throws IOException {
        if (request.aiEvidenceId() == null || request.aiEvidenceId().isBlank()) {
            throw ApiException.badRequest("AI_EVIDENCE_REQUIRED", "生成 AI 候选补丁需要关联 AI 证据");
        }
        EvidenceEntity evidence = evidences.selectById(request.aiEvidenceId());
        if (evidence == null || !finding.id.equals(evidence.findingId) || !"AI".equals(evidence.kind)) {
            throw ApiException.badRequest("AI_EVIDENCE_INVALID", "AI 证据不存在或不属于当前问题");
        }
        JsonNode payload = readJson(evidence.payload);
        String confidence = payload.path("confidenceBand").asText("manual_review").toLowerCase(Locale.ROOT);
        if (!Set.of("high", "medium").contains(confidence)) {
            throw ApiException.badRequest("AI_CONFIDENCE_TOO_LOW", "该 AI 建议置信度不足，只能人工处理");
        }

        ScanTaskEntity scan = scans.selectById(finding.scanTaskId);
        ProjectVersionEntity version = versions.selectById(scan.versionId);
        Path source = storage.resolveVersionFile(version.projectId, version.id, finding.pagePath);
        String before = Files.readString(source, StandardCharsets.UTF_8);
        SourcePatchService.PatchResult result = sourcePatches.patchAiAttribute(
                before, finding.selector, finding.ruleId, request.candidateText(), request.decorative());
        String provider = truncate(blankToNull(payload.path("provider").asText(null)), 50);
        String model = truncate(blankToNull(payload.path("model").asText(null)), 120);

        FixPatchEntity existing = pending(finding.id);
        if (existing != null) {
            boolean same = "AI_CANDIDATE".equals(existing.patchType)
                    && Objects.equals(existing.sourceEvidenceId, evidence.id)
                    && Objects.equals(existing.candidateText, result.normalizedText())
                    && Objects.equals(Boolean.TRUE.equals(existing.decorative), request.decorative());
            if (same) return existing;
            throw ApiException.conflict("PATCH_ALREADY_PENDING", "该问题已有不同内容的待审补丁，请先拒绝原补丁");
        }
        return savePatch(finding, version, source, before, result.content(), "AI_CANDIDATE",
                result.normalizedText(), request.decorative(), evidence.id, provider, model);
    }

    private FixPatchEntity savePatch(FindingEntity finding, ProjectVersionEntity version, Path source,
                                     String before, String after, String patchType, String candidateText,
                                     boolean decorative, String sourceEvidenceId, String provider, String model) {
        validatePatch(before, after);
        List<String> originalLines = Arrays.asList(before.split("\\R", -1));
        List<String> revisedLines = Arrays.asList(after.split("\\R", -1));
        String diff = String.join("\n", UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + finding.pagePath, "b/" + finding.pagePath, originalLines,
                DiffUtils.diff(originalLines, revisedLines), 3));
        FixPatchEntity patch = new FixPatchEntity();
        patch.id = Ulids.next(); patch.findingId = finding.id; patch.baseVersionId = version.id;
        patch.targetFile = finding.pagePath; patch.beforeSha = Hashes.sha256(source);
        patch.patchedContent = after; patch.diffText = diff; patch.patchType = patchType;
        patch.candidateText = candidateText; patch.decorative = decorative;
        patch.sourceEvidenceId = sourceEvidenceId; patch.sourceProvider = provider; patch.sourceModel = model;
        patch.status = "PENDING";
        patch.verification = writeJson(Map.of("syntax", "passed", "fileCount", 1,
                "networkUrlAdded", false, "semanticReview", "AI_CANDIDATE".equals(patchType) ? "PENDING" : "NOT_REQUIRED",
                "retestStatus", "NOT_STARTED"));
        patch.createdAt = LocalDateTime.now(); patch.updatedAt = patch.createdAt;
        patches.insert(patch);
        return patch;
    }

    @Transactional
    public ApplyResult apply(String patchId, boolean reviewAccepted) throws IOException {
        FixPatchEntity patch = get(patchId);
        if (Set.of("APPLIED", "VERIFIED").contains(patch.status))
            return new ApplyResult(patch, null);
        if (!"PENDING".equals(patch.status)) throw ApiException.badRequest("PATCH_NOT_PENDING", "补丁当前不能应用");
        if ("AI_CANDIDATE".equals(patch.patchType) && !reviewAccepted) {
            throw ApiException.badRequest("AI_REVIEW_REQUIRED", "应用 AI 候选补丁前必须完成用户确认");
        }
        ProjectVersionEntity base = versions.selectById(patch.baseVersionId);
        Path baseFile = storage.resolveVersionFile(base.projectId, base.id, patch.targetFile);
        if (!Hashes.sha256(baseFile).equals(patch.beforeSha)) {
            patch.status = "INVALID"; patch.updatedAt = LocalDateTime.now(); patches.updateById(patch);
            throw ApiException.badRequest("PATCH_BASE_CHANGED", "基准版本文件摘要已变化，补丁已失效");
        }
        int number = versions.selectList(new QueryWrapper<ProjectVersionEntity>().eq("project_id", base.projectId))
                .stream().map(v -> v.versionNo).max(Integer::compareTo).orElse(0) + 1;
        String versionId = Ulids.next();
        Path targetRoot = storage.versionSource(base.projectId, versionId);
        storage.copyTree(storage.versionSource(base.projectId, base.id), targetRoot);
        Path targetFile = targetRoot.resolve(patch.targetFile).normalize();
        if (!targetFile.startsWith(targetRoot)) throw ApiException.badRequest("PATCH_PATH_INVALID", "补丁目标路径不合法");
        Files.writeString(targetFile, patch.patchedContent, StandardCharsets.UTF_8);

        ProjectVersionEntity version = new ProjectVersionEntity();
        version.id = versionId; version.projectId = base.projectId; version.baseVersionId = base.id;
        version.versionNo = number; version.sourceType = "AI_CANDIDATE".equals(patch.patchType) ? "AI_PATCH" : "PATCH";
        version.storagePath = storage.relativeToRoot(targetRoot); version.entryPage = base.entryPage;
        version.manifestHash = storage.manifestHash(targetRoot); version.createdAt = LocalDateTime.now();
        versions.insert(version);
        ProjectEntity project = projects.selectById(base.projectId);
        project.currentVersionId = version.id; project.updatedAt = LocalDateTime.now(); projects.updateById(project);
        patch.status = "APPLIED"; patch.newVersionId = version.id;
        if ("AI_CANDIDATE".equals(patch.patchType)) patch.reviewedAt = LocalDateTime.now();
        patch.verification = writeJson(Map.of("syntax", "passed", "fileCount", 1,
                "networkUrlAdded", false,
                "semanticReview", "AI_CANDIDATE".equals(patch.patchType) ? "HUMAN_ACCEPTED" : "NOT_REQUIRED",
                "aiReanalysis", false, "retestStatus", "PENDING"));
        patch.updatedAt = LocalDateTime.now();
        patches.updateById(patch);
        FindingEntity finding = findings.selectById(patch.findingId);
        ScanTaskEntity sourceScan = scans.selectById(finding.scanTaskId);
        ScanTaskEntity rescan = scanService.createForVersion(version, rescanRequest(sourceScan));
        var verification = new java.util.LinkedHashMap<String,Object>(json.readValue(patch.verification, java.util.Map.class));
        verification.put("rescanId", rescan.id);
        patch.verification = writeJson(verification);
        patches.updateById(patch);
        return new ApplyResult(patch, rescan);
    }

    public FixPatchEntity reject(String patchId) {
        FixPatchEntity patch = get(patchId);
        if (!"PENDING".equals(patch.status)) throw ApiException.badRequest("PATCH_NOT_PENDING", "补丁当前不能拒绝");
        patch.status = "REJECTED"; patch.updatedAt = LocalDateTime.now(); patches.updateById(patch);
        return patch;
    }

    String patchLanguage(String source, String language) {
        String lang = language == null || language.isBlank() ? "zh-CN" : language.strip();
        if (!lang.matches("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$"))
            throw ApiException.badRequest("LANG_INVALID", "语言标签格式不合法");
        Matcher html = Pattern.compile("(?i)<html\\b([^>]*)>").matcher(source);
        if (!html.find()) throw ApiException.badRequest("HTML_ROOT_NOT_FOUND", "未找到 html 根标签");
        String attrs = html.group(1).replaceAll("(?i)\\s+lang\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        return source.substring(0, html.start()) + "<html" + attrs + " lang=\"" + lang + "\">" + source.substring(html.end());
    }

    String patchLabel(String source, String selector) {
        if (selector == null || !selector.startsWith("#"))
            throw ApiException.badRequest("LABEL_PATCH_UNSAFE", "只有带稳定 ID 的控件才能生成标签关联补丁");
        String id = selector.substring(1).replace("\\", "");
        if (!id.matches("[A-Za-z][A-Za-z0-9_.:-]*"))
            throw ApiException.badRequest("LABEL_PATCH_UNSAFE", "控件 ID 不适合自动补丁");
        Pattern input = Pattern.compile("(?is)<(?:input|select|textarea)\\b[^>]*\\bid\\s*=\\s*(['\"])" + Pattern.quote(id) + "\\1[^>]*>");
        Matcher control = input.matcher(source);
        if (!control.find()) throw ApiException.badRequest("CONTROL_NOT_FOUND", "无法在源码中定位控件");
        int from = Math.max(0, control.start() - 800);
        String prefix = source.substring(from, control.start());
        Matcher label = Pattern.compile("(?is)<label\\b(?![^>]*\\bfor\\s*=)([^>]*)>([^<]{1,200})</label>\\s*$").matcher(prefix);
        if (!label.find()) throw ApiException.badRequest("VISIBLE_LABEL_NOT_FOUND", "控件附近没有可安全关联的可见标签");
        int openingEnd = from + label.start() + label.group(0).indexOf('>');
        return source.substring(0, openingEnd) + " for=\"" + id + "\"" + source.substring(openingEnd);
    }

    String patchFocus(String source) {
        if (source.contains("data-accessibility-fix=\"focus-visible\"")) return source;
        Matcher head = Pattern.compile("(?i)</head>").matcher(source);
        if (head.find()) return source.substring(0, head.start()) + FOCUS_STYLE + source.substring(head.start());
        return FOCUS_STYLE + source;
    }

    void validatePatch(String before, String after) {
        if (before.equals(after)) throw ApiException.badRequest("PATCH_EMPTY", "模板未产生有效修改");
        if (after.length() - before.length() > 20_000) throw ApiException.badRequest("PATCH_TOO_LARGE", "补丁修改范围过大");
        String lowerBefore = before.toLowerCase(Locale.ROOT);
        String lowerAfter = after.toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("http://", "https://", "javascript:", "<script")) {
            if (occurrences(lowerAfter, forbidden) > occurrences(lowerBefore, forbidden))
                throw ApiException.badRequest("PATCH_UNSAFE", "补丁不得增加网络地址或脚本");
        }
        if (Jsoup.parse(after).selectFirst("html") == null) throw ApiException.badRequest("PATCH_HTML_INVALID", "补丁后的 HTML 无法解析");
    }

    private int occurrences(String value, String needle) {
        int count = 0;
        for (int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }

    private FixPatchEntity pending(String findingId) {
        return patches.selectOne(new QueryWrapper<FixPatchEntity>()
                .eq("finding_id", findingId).eq("status", "PENDING")
                .orderByDesc("created_at").last("LIMIT 1"));
    }

    ScanService.ScanRequest rescanRequest(ScanTaskEntity source) {
        List<String> snapshotRules = ScanService.RULE_VERSION.equals(source.ruleVersion)
                ? ScanService.ALL_RULES : ScanService.ALL_RULES.subList(0, 8);
        try {
            JsonNode root = readJson(source.scanConfig);
            List<String> pages = strings(root.path("pages"));
            List<String> rules = strings(root.path("rules"));
            if (rules.isEmpty()) rules = snapshotRules;
            boolean keyboard = root.path("enableKeyboardTest").asBoolean(true);
            return new ScanService.ScanRequest(pages, false, false, keyboard, rules);
        } catch (RuntimeException ignored) {
            return new ScanService.ScanRequest(List.of(), false, false, true, snapshotRules);
        }
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText()); });
        return List.copyOf(values);
    }

    private JsonNode readJson(String value) {
        try { return json.readTree(value == null ? "{}" : value); }
        catch (Exception ex) { throw ApiException.badRequest("AI_EVIDENCE_INVALID", "AI 证据格式无法解析"); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("无法序列化补丁数据", ex); }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    public record PatchRequest(String language, String aiEvidenceId, String candidateText, boolean decorative) {}
    public record ApplyResult(FixPatchEntity patch, ScanTaskEntity rescan) {}
}
