package com.shijie.accessibility.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import tools.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.shijie.accessibility.common.Hashes;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.mapper.*;
import com.shijie.accessibility.model.*;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ReportService {
    private final ScanTaskMapper scans;
    private final FindingMapper findings;
    private final EvidenceMapper evidences;
    private final ProjectMapper projects;
    private final ProjectVersionMapper versions;
    private final ArtifactMapper artifacts;
    private final FixPatchMapper patches;
    private final AnalysisTraceMapper traces;
    private final FindingReviewMapper reviews;
    private final StorageService storage;
    private final ObjectMapper json;
    private final ComparisonService comparisons;

    public ReportService(ScanTaskMapper scans, FindingMapper findings, EvidenceMapper evidences,
                         ProjectMapper projects, ProjectVersionMapper versions, ArtifactMapper artifacts,
                         FixPatchMapper patches, AnalysisTraceMapper traces, FindingReviewMapper reviews,
                         StorageService storage, ObjectMapper json, ComparisonService comparisons) {
        this.scans=scans; this.findings=findings; this.evidences=evidences; this.projects=projects;
        this.versions=versions; this.artifacts=artifacts; this.patches=patches; this.traces=traces;
        this.reviews=reviews; this.storage=storage; this.json=json;
        this.comparisons=comparisons;
    }

    public ReportFile generate(Snapshot snapshot, String snapshotId, String contentHash, String format) throws Exception {
        String scanId = snapshot.scan.id;
        String reportId = Ulids.next();
        Path directory = storage.reportDirectory(snapshot.project.id, reportId);
        if ("json".equalsIgnoreCase(format)) {
            Path file = directory.resolve("report.json");
            Files.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                    new ReportSnapshotService.Frozen(snapshotId, contentHash, snapshot.generatedAt, snapshot)));
            saveArtifact(snapshot, reportId, file, "REPORT_JSON");
            return new ReportFile(file, MediaTypes.JSON, "accessibility-report-" + snapshotId + ".json", reportId);
        }
        if (!"pdf".equalsIgnoreCase(format)) throw com.shijie.accessibility.common.ApiException.badRequest("REPORT_FORMAT", "报告格式只支持 pdf 或 json");
        Path file = directory.resolve("report.pdf");
        byte[] reportFont;
        try (var input = new ClassPathResource("fonts/NotoSansSC.ttf").getInputStream()) {
            reportFont = input.readAllBytes();
        }
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                     .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             Page page = browser.newPage()) {
            page.route("http://report.local/NotoSansSC.ttf", route -> route.fulfill(
                    new Route.FulfillOptions().setBodyBytes(reportFont).setContentType("font/ttf")));
            page.setContent(renderHtml(snapshot).replace("<h1>视界无障碍", "<p>报告快照：" + escape(snapshotId)
                    + "<br>内容哈希：" + escape(contentHash) + "<br>生成时间：" + snapshot.generatedAt + "</p><h1>视界无障碍"),
                    new Page.SetContentOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.LOAD));
            page.evaluate("() => document.fonts.ready");
            page.pdf(new Page.PdfOptions().setPath(file).setFormat("A4").setPrintBackground(true)
                    .setMargin(new com.microsoft.playwright.options.Margin().setTop("14mm").setBottom("14mm").setLeft("14mm").setRight("14mm")));
        }
        saveArtifact(snapshot, reportId, file, "REPORT_PDF");
        return new ReportFile(file, MediaTypes.PDF, "accessibility-report-" + snapshotId + ".pdf", reportId);
    }

    public Snapshot snapshot(String scanId) {
        ScanTaskEntity scan = scans.selectById(scanId);
        if (scan == null) throw com.shijie.accessibility.common.ApiException.notFound("扫描任务不存在");
        ProjectEntity project = projects.selectById(scan.projectId);
        ProjectVersionEntity version = versions.selectById(scan.versionId);
        List<FindingEntity> items = findings.selectList(new QueryWrapper<FindingEntity>().eq("scan_task_id", scanId)
                .orderByAsc("rule_id", "page_path"));
        Map<String, List<EvidenceEntity>> evidenceMap = new LinkedHashMap<>();
        for (FindingEntity finding : items) evidenceMap.put(finding.id,
                evidences.selectList(new QueryWrapper<EvidenceEntity>().eq("finding_id", finding.id)));
        Map<String, List<FindingReviewEntity>> reviewMap = new LinkedHashMap<>();
        for (FindingEntity finding : items) reviewMap.put(finding.id,
                reviews.selectList(new QueryWrapper<FindingReviewEntity>().eq("finding_id", finding.id)
                        .orderByAsc("created_at")));
        List<AnalysisTraceEntity> traceItems = traces.selectList(new QueryWrapper<AnalysisTraceEntity>()
                .eq("scan_task_id", scanId).orderByAsc("page_path", "trace_type"));
        List<FixPatchEntity> patchItems = patches.selectList(new QueryWrapper<FixPatchEntity>()
                .and(q -> q.eq("new_version_id", version.id).or().eq("base_version_id", version.id))
                .orderByAsc("created_at"));
        Comparison comparison = comparison(version, scan, items);
        return new Snapshot(project, version, scan, items, evidenceMap, reviewMap, traceItems,
                patchItems, comparison, LocalDateTime.now(),
                "内部风险分 = max(0, 100 - 10×高风险 - 4×中风险 - 低风险)；分数越高表示当前规则下检出风险越少。自动候选不计分，按需记录为风险后按相应等级计分，已驳回结果不计分。",
                "本报告仅覆盖已启用规则和已扫描页面；R009 为启发式候选而非 WCAG 违规判定，不构成完整无障碍合规结论。");
    }

    private Comparison comparison(ProjectVersionEntity version, ScanTaskEntity current, List<FindingEntity> currentItems) {
        if (version.baseVersionId == null) return null;
        List<ScanTaskEntity> candidates = scans.selectList(new QueryWrapper<ScanTaskEntity>()
                .eq("version_id", version.baseVersionId)
                .in("status", List.of("COMPLETED", "PARTIAL"))
                .lt("created_at", current.createdAt)
                .orderByDesc("created_at").last("LIMIT 1"));
        if (candidates.isEmpty()) return null;
        ScanTaskEntity before = candidates.get(0);
        // Prefer the exact source scan of the patch whose recorded retest is this scan.
        for (FixPatchEntity patch : patches.selectList(new QueryWrapper<FixPatchEntity>().eq("new_version_id",version.id))) {
            if(current.id.equals(json.readTree(patch.verification==null?"{}":patch.verification).path("rescanId").asText())) {
                FindingEntity original=findings.selectById(patch.findingId);
                if(original!=null) before=scans.selectById(original.scanTaskId);
                break;
            }
        }
        List<FindingEntity> beforeItems = findings.selectList(new QueryWrapper<FindingEntity>()
                .eq("scan_task_id", before.id));
        if (!ComparisonService.incompatibilities(before, current, json).isEmpty()) return null;
        Set<String> beforeKeys = new HashSet<>();
        Set<String> currentKeys = new HashSet<>();
        beforeItems.forEach(f -> beforeKeys.add(f.fingerprint));
        currentItems.forEach(f -> currentKeys.add(f.fingerprint));
        int fixed = (int) beforeKeys.stream().filter(key -> !currentKeys.contains(key)).count();
        int introduced = (int) currentKeys.stream().filter(key -> !beforeKeys.contains(key)).count();
        int remaining = (int) currentKeys.stream().filter(beforeKeys::contains).count();
        return new Comparison(before.id, current.id, beforeKeys.size(), currentKeys.size(),
                before.riskScore, current.riskScore, fixed, introduced, remaining,
                comparisons.compare(current.projectId, before.id, current.id));
    }

    private String renderHtml(Snapshot s) throws Exception {
        StringBuilder rows = new StringBuilder();
        StringBuilder screenshots = new StringBuilder();
        StringBuilder aiRows = new StringBuilder();
        Set<String> includedArtifacts = new HashSet<>();
        for (FindingEntity f : s.findings) rows.append("<tr><td>").append(escape(f.ruleId)).append("</td><td>")
                .append(escape(category(f))).append("</td><td>").append(escape(f.severity)).append("</td><td>")
                .append(escape(f.pagePath)).append("</td><td>")
                .append(escape(f.title)).append("</td><td>").append(escape(f.selector)).append("</td></tr>");
        for (FindingEntity f : s.findings) {
            for (EvidenceEntity evidence : s.evidences.getOrDefault(f.id, List.of())) {
                if (!"AI".equals(evidence.kind)) continue;
                aiRows.append("<tr><td>").append(escape(f.ruleId)).append("</td><td>")
                        .append(escape(f.pagePath)).append("</td><td>").append(escape(f.selector)).append("</td><td>")
                        .append(escape(evidence.summary)).append("</td></tr>");
            }
            if (!"HIGH".equals(f.severity) || includedArtifacts.size() >= 5) continue;
            for (EvidenceEntity evidence : s.evidences.getOrDefault(f.id, List.of())) {
                if (evidence.artifactId == null || !includedArtifacts.add(evidence.artifactId)) continue;
                ArtifactEntity artifact = artifacts.selectById(evidence.artifactId);
                if (artifact == null) continue;
                byte[] bytes = Files.readAllBytes(storage.resolveArtifact(artifact.relativePath));
                screenshots.append("<div class=\"shot\"><h3>").append(escape(f.ruleId + " · " + f.pagePath))
                        .append("</h3><img src=\"data:image/png;base64,")
                        .append(Base64.getEncoder().encodeToString(bytes)).append("\"></div>");
                break;
            }
        }
        StringBuilder patchRows = new StringBuilder();
        for (FixPatchEntity patch : s.patches) patchRows.append("<tr><td>")
                .append(escape("AI_CANDIDATE".equals(patch.patchType) ? "AI 候选修复" : "模板修复"))
                .append("</td><td>").append(escape(patch.targetFile))
                .append("</td><td>").append(escape(patchCandidate(patch)))
                .append("</td><td>").append(escape(modelSource(patch)))
                .append("</td><td>").append(escape(patch.reviewedAt == null ? "-" : patch.reviewedAt.toString()))
                .append("</td><td>").append(escape(verificationSummary(patch.verification))).append("</td></tr>");
        String comparison = s.comparison == null ? "" : """
                <h2>修复前后对比</h2><div class="summary">
                <div class="card">原扫描记录<br><b>%d</b></div><div class="card">复测记录<br><b>%d</b></div>
                <div class="card">未再检出<br><b>%d</b></div><div class="card">仍存在<br><b>%d</b></div><div class="card">新增<br><b>%d</b></div>
                </div><p>内部风险分：%s → %s</p>
                """.formatted(s.comparison.beforeTotal, s.comparison.afterTotal, s.comparison.fixed,
                s.comparison.remaining, s.comparison.introduced,
                Objects.toString(s.comparison.beforeRiskScore, "-"), Objects.toString(s.comparison.afterRiskScore, "-"));
        if (s.comparison != null && s.comparison.details != null) {
            comparison += "<p>可选审阅结论变化：" + s.comparison.details.reviewChanged()
                    + "；目标基础规则修复通过：" + s.comparison.details.verifiedFindingIds().size()
                    + "。未再检出仅描述本次检出差异，不代表完整合规。</p>";
        }
        var config = json.readTree(s.scan.scanConfig);
        String scope = "<h2>扫描范围与状态</h2><p>状态：" + escape(s.scan.status)
                + "；规则版本：" + escape(s.scan.ruleVersion) + "；页面："
                + escape(config.path("resolvedPages").isMissingNode()?config.path("pages").toString():config.path("resolvedPages").toString())
                + "；规则：" + escape(config.path("rules").toString()) + "；键盘测试："
                + config.path("enableKeyboardTest").asBoolean() + "</p>";
        StringBuilder evidenceRows = new StringBuilder("<h2>问题证据摘要</h2>");
        for (FindingEntity f : s.findings) {
            evidenceRows.append("<h3>").append(escape(f.ruleId + " · " + f.pagePath + " · " + f.selector)).append("</h3>");
            for (EvidenceEntity evidence : s.evidences.getOrDefault(f.id,List.of())) {
                evidenceRows.append("<p>").append(escape(evidence.summary)).append("</p>");
                if (!"AI".equals(evidence.kind)) {
                    String html=json.readTree(evidence.payload).path("outerHtml").asText("");
                    if(!html.isBlank()) evidenceRows.append("<pre>").append(escape(html)).append("</pre>");
                }
            }
        }
        String aiSection = aiRows.isEmpty() ? "" : """
                <h2>AI 语义分析建议</h2>
                <p>以下内容仅为未自动确认的风险候选，不计入内部风险分，也不构成自动修复结论；项目成员可按需审阅。</p>
                <table><thead><tr><th>规则</th><th>页面</th><th>元素</th><th>AI 建议</th></tr></thead><tbody>%s</tbody></table>
                """.formatted(aiRows);
        StringBuilder traceRows = new StringBuilder();
        for (AnalysisTraceEntity trace : s.traces) traceRows.append("<tr><td>")
                .append(escape(trace.pagePath)).append("</td><td>").append(escape(trace.traceType))
                .append("</td><td>").append(escape(trace.status)).append("</td></tr>");
        StringBuilder reviewRows = new StringBuilder();
        for (FindingEntity finding : s.findings) {
            for (FindingReviewEntity review : s.reviews.getOrDefault(finding.id, List.of())) {
                reviewRows.append("<tr><td>").append(escape(finding.ruleId)).append("</td><td>")
                        .append(escape(finding.pagePath)).append("</td><td>").append(escape(review.action))
                        .append("</td><td>").append(Boolean.TRUE.equals(review.reused) ? "是" : "否")
                        .append("</td><td>").append(escape(review.comment)).append("</td></tr>");
            }
        }
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>
                @font-face{font-family:"Noto Sans SC Embedded";src:url("http://report.local/NotoSansSC.ttf") format("truetype");font-weight:100 900;font-display:block}
                body{font-family:"Noto Sans SC Embedded",sans-serif;color:#172033;font-size:12px;overflow-wrap:anywhere}h1{color:#163d70}h2,h3{break-after:avoid}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-family:inherit}tr{break-inside:avoid}thead{display:table-header-group}
                .issues{table-layout:fixed}.issues th:nth-child(1){width:7%%}.issues th:nth-child(2){width:12%%}.issues th:nth-child(3){width:10%%}.issues th:nth-child(4){width:13%%}.issues th:nth-child(5){width:23%%}.issues th:nth-child(6){width:35%%}.issues td:nth-child(1),.issues td:nth-child(3){white-space:nowrap}
                .summary{display:flex;gap:12px;margin:18px 0}.card{border:1px solid #dbe4ef;border-radius:8px;padding:12px;min-width:100px}
                .score{font-size:28px;font-weight:700;color:#176b87}table{width:100%%;border-collapse:collapse}th,td{border:1px solid #dbe4ef;padding:7px;text-align:left}th{background:#edf5fb}.note{margin-top:20px;color:#5d6b7e}.shot{break-inside:avoid;margin-top:16px}.shot img{width:100%%;max-height:180mm;object-fit:contain;border:1px solid #dbe4ef}
                </style></head><body><h1>视界无障碍 · 检测报告</h1><p>项目：%s　版本：V%d　扫描：%s</p>
                <div class="summary"><div class="card"><div>内部风险分</div><div class="score">%d</div></div><div class="card">高风险<br><b>%d</b></div><div class="card">中风险<br><b>%d</b></div><div class="card">低风险<br><b>%d</b></div><div class="card">自动候选<br><b>%d</b></div></div>
                %s
                <p>%s</p>%s<table class="issues"><thead><tr><th>规则</th><th>结论类别</th><th>等级</th><th>页面</th><th>问题</th><th>元素</th></tr></thead><tbody>%s</tbody></table>
                %s
                <h2>交互轨迹摘要</h2><table><thead><tr><th>页面</th><th>轨迹</th><th>状态</th></tr></thead><tbody>%s</tbody></table>
                <h2>可选审阅历史</h2><table><thead><tr><th>规则</th><th>页面</th><th>操作</th><th>复用结论</th><th>说明</th></tr></thead><tbody>%s</tbody></table>
                <h2>修复与复测记录</h2>
                <p>AI 候选修复仅验证基础规则是否消失；候选文本语义由用户确认，系统不会将其表述为完整合规验证。</p>
                <table><thead><tr><th>类型</th><th>文件</th><th>采用内容</th><th>来源模型</th><th>确认时间</th><th>验证结果</th></tr></thead><tbody>%s</tbody></table>
                <h2>高风险问题截图证据</h2>%s%s
                <p class="note">%s</p></body></html>
                """.formatted(escape(s.project.name), s.version.versionNo, escape(s.scan.id),
                Optional.ofNullable(s.scan.riskScore).orElse(0), s.scan.highCount, s.scan.mediumCount, s.scan.lowCount,
                Optional.ofNullable(s.scan.reviewCount).orElse(0), comparison, escape(s.formula), scope, rows, aiSection,
                traceRows, reviewRows, patchRows, screenshots, evidenceRows, escape(s.disclaimer));
    }

    private String category(FindingEntity finding) {
        return switch (Objects.toString(finding.status, "")) {
            case "NEEDS_REVIEW" -> "自动候选";
            case "CONFIRMED" -> "已确认风险";
            case "DISMISSED" -> "已驳回";
            default -> "确定性问题";
        };
    }

    private void saveArtifact(Snapshot snapshot, String reportId, Path file, String kind) throws Exception {
        ArtifactEntity artifact = new ArtifactEntity(); artifact.id = reportId; artifact.projectId = snapshot.project.id;
        artifact.versionId = snapshot.version.id; artifact.scanTaskId = snapshot.scan.id; artifact.kind = kind;
        artifact.relativePath = storage.relativeToRoot(file); artifact.sha256 = Hashes.sha256(file);
        artifact.sizeBytes = Files.size(file); artifact.createdAt = LocalDateTime.now(); artifacts.insert(artifact);
    }

    private String patchCandidate(FixPatchEntity patch) {
        if (!"AI_CANDIDATE".equals(patch.patchType)) return "-";
        return Boolean.TRUE.equals(patch.decorative) ? "装饰性图片（alt=\"\"）"
                : Objects.toString(patch.candidateText, "-");
    }

    private String modelSource(FixPatchEntity patch) {
        if (!"AI_CANDIDATE".equals(patch.patchType)) return "-";
        return Objects.toString(patch.sourceProvider, "-") + " / " + Objects.toString(patch.sourceModel, "-");
    }

    private String verificationSummary(String value) {
        try {
            var node = json.readTree(value == null ? "{}" : value);
            String status = node.path("retestStatus").asText("NOT_STARTED");
            if ("COMPLETED".equals(status) && node.path("ruleCleared").asBoolean(false)) {
                return "HUMAN_ACCEPTED".equals(node.path("semanticReview").asText())
                        ? "基础规则复测通过，语义已人工确认，未再次调用 AI"
                        : "基础规则复测通过";
            }
            if ("COMPLETED".equals(status)) return "基础规则复测未通过";
            if (Set.of("FAILED", "CANCELLED", "PARTIAL").contains(status)) return "复测未完成，可重新扫描";
            if ("PENDING".equals(status)) return "等待自动复测";
            return "尚未开始复测";
        } catch (Exception ignored) {
            return Objects.toString(value, "-");
        }
    }

    private String escape(String value) { return value == null ? "" : value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }

    public record Snapshot(ProjectEntity project, ProjectVersionEntity version, ScanTaskEntity scan,
                           List<FindingEntity> findings, Map<String,List<EvidenceEntity>> evidences,
                           Map<String,List<FindingReviewEntity>> reviews, List<AnalysisTraceEntity> traces,
                           List<FixPatchEntity> patches, Comparison comparison, LocalDateTime generatedAt,
                           String formula, String disclaimer) {}
    public record Comparison(String beforeScanId, String afterScanId, int beforeTotal, int afterTotal,
                             Integer beforeRiskScore, Integer afterRiskScore, int fixed, int introduced, int remaining,
                             ComparisonService.Result details) {}
    public record ReportFile(Path path, String contentType, String filename, String artifactId) {}
    private static final class MediaTypes { static final String JSON="application/json"; static final String PDF="application/pdf"; }
}
