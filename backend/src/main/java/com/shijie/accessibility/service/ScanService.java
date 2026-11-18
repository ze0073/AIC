package com.shijie.accessibility.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import tools.jackson.databind.ObjectMapper;
import com.shijie.accessibility.ai.AiAnalysisService;
import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Hashes;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.mapper.*;
import com.shijie.accessibility.model.*;
import com.shijie.accessibility.scanner.BrowserScanner;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ScanService {
    @org.springframework.beans.factory.annotation.Autowired private com.shijie.accessibility.security.ProjectAccessService access;
    @org.springframework.beans.factory.annotation.Value("${app.hosting.worker-only:false}") private boolean workerOnly;
    public static final String RULE_VERSION = "P0-1.0+P1-0.4";
    public static final List<String> ALL_RULES = List.of("R001","R002","R003","R004","R005","R006","R007","R008","R009");

    private final ScanTaskMapper scans;
    private final ScanLogMapper logs;
    private final FindingMapper findings;
    private final EvidenceMapper evidences;
    private final ArtifactMapper artifacts;
    private final FixPatchMapper patches;
    private final AnalysisTraceMapper traces;
    private final FindingReviewMapper reviews;
    private final ReviewPolicyMapper reviewPolicies;
    private final ProjectService projects;
    private final BrowserScanner scanner;
    private final AiAnalysisService aiAnalysis;
    private final StorageService storage;
    private final ObjectMapper json;
    private final TaskExecutor executor;
    private final ScanEvents events;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private StreamRevocations revocations;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final Set<String> cancellation = ConcurrentHashMap.newKeySet();

    public ScanService(ScanTaskMapper scans, ScanLogMapper logs, FindingMapper findings,
                       EvidenceMapper evidences, ArtifactMapper artifacts, FixPatchMapper patches,
                       AnalysisTraceMapper traces, FindingReviewMapper reviews, ReviewPolicyMapper reviewPolicies,
                       ProjectService projects,
                       BrowserScanner scanner, AiAnalysisService aiAnalysis, StorageService storage, ObjectMapper json,
                       @Qualifier("scanExecutor") TaskExecutor executor, ScanEvents events) {
        this.scans = scans; this.logs = logs; this.findings = findings; this.evidences = evidences;
        this.artifacts = artifacts; this.patches = patches; this.traces = traces; this.reviews = reviews;
        this.reviewPolicies = reviewPolicies; this.projects = projects; this.scanner = scanner;
        this.aiAnalysis = aiAnalysis; this.storage = storage;
        this.json = json; this.executor = executor; this.events = events;
    }

    @PostConstruct
    void recoverInterrupted() {
        if(workerOnly)return;
        for (ScanTaskEntity scan : scans.selectList(new QueryWrapper<ScanTaskEntity>().eq("status", "RUNNING"))) {
            scan.status = "FAILED";
            scan.stage = "AGGREGATION";
            scan.errorMessage = "服务重启导致任务中断";
            scan.finishedAt = LocalDateTime.now();
            scans.updateById(scan);
        }
    }

    @Transactional
    public ScanTaskEntity create(String projectId, ScanRequest request) {
        ProjectEntity project = projects.get(projectId);
        if (project.currentVersionId == null) throw ApiException.badRequest("PROJECT_NOT_IMPORTED", "请先导入网页项目");
        return createForVersion(projects.version(project.currentVersionId), request);
    }

    @Transactional
    public ScanTaskEntity createForVersion(ProjectVersionEntity version, ScanRequest request) {
        if(access!=null)access.require(version.projectId,"MEMBER");
        if (version.entryPage==null || !version.entryPage.toLowerCase(Locale.ROOT).matches(".*\\.html?"))
            throw ApiException.badRequest("SOURCE_NOT_HTML", "此源码包没有 HTML 页面，请部署后执行在线复测");
        if (request.enableAi() && !request.aiConsent()) {
            throw ApiException.badRequest("AI_CONSENT_REQUIRED", "启用 AI 分析前需要确认最小截图和代码上下文将发送至外部服务");
        }
        List<String> rules = request.rules() == null || request.rules().isEmpty() ? ALL_RULES : request.rules();
        if (!ALL_RULES.containsAll(rules)) throw ApiException.badRequest("UNKNOWN_RULE", "扫描配置包含未知规则");
        ScanTaskEntity task = new ScanTaskEntity();
        task.id = Ulids.next(); task.projectId = version.projectId; task.versionId = version.id;
        task.status = "PENDING"; task.stage = "FILE_CHECK"; task.progress = 0;
        task.foundCount = task.reviewCount = task.highCount = task.mediumCount = task.lowCount = 0;
        task.scanConfig = writeJson(Map.of("pages", request.pages() == null ? List.of() : request.pages(),
                "enableAi", request.enableAi(), "aiConsent", request.aiConsent(),
                "enableKeyboardTest", request.enableKeyboardTest(), "rules", rules));
        task.ruleVersion = RULE_VERSION; task.createdAt = LocalDateTime.now();
        task.cancelRequested=false;task.createdBy=access==null?null:access.currentUser().id();
        if(jdbc!=null)PersistentScanDispatcher.capacity(jdbc,false);
        scans.insert(task);
        log(task, "INFO", "扫描任务已创建");
        Runnable enqueue = () -> { if(jdbc==null)executor.execute(() -> execute(task.id, request)); };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { enqueue.run(); }
            });
        } else enqueue.run();
        return task;
    }

    public ScanTaskEntity get(String id) {
        ScanTaskEntity scan = scans.selectById(id);
        if (scan == null) throw ApiException.notFound("扫描任务不存在");
        return scan;
    }

    public List<ScanTaskEntity> recentForProject(String projectId) {
        return scans.selectList(new QueryWrapper<ScanTaskEntity>().eq("project_id", projectId)
                .orderByDesc("created_at").last("LIMIT 20"));
    }

    public SseEmitter subscribe(String scanId) {var task=get(scanId);var emitter=events.subscribe(task);if(revocations!=null&&access!=null){String user=access.currentUser().id();long version=access.version(user);revocations.watch(emitter,()->access.canRead(task.projectId,user,version));}return emitter;}

    public ScanTaskEntity cancel(String scanId) {
        ScanTaskEntity scan = get(scanId);
        if (Set.of("PENDING", "RUNNING").contains(scan.status)) {cancellation.add(scanId);if(jdbc!=null){jdbc.update("UPDATE scan_tasks SET cancel_requested=TRUE WHERE id=?",scanId);if("PENDING".equals(scan.status)){jdbc.update("UPDATE scan_tasks SET status='CANCELLED',finished_at=?,error_message='排队时已取消' WHERE id=? AND status='PENDING'",LocalDateTime.now(),scanId);events.publish(get(scanId));}}}
        scan=get(scanId);
        return scan;
    }

    public ScanTaskEntity rescan(String versionId, ScanRequest request) {
        return createForVersion(projects.version(versionId), request);
    }

    public FindingsPage listFindings(String scanId, String severity, String ruleId, String pagePath,
                                     String status, String source, String sort, int page, int size) {
        get(scanId);
        if (!Set.of("severity", "review").contains(sort == null ? "severity" : sort.toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("INVALID_SORT", "问题列表排序仅支持 severity 或 review");
        }
        QueryWrapper<FindingEntity> query = new QueryWrapper<FindingEntity>().eq("scan_task_id", scanId);
        if (severity != null && !severity.isBlank()) query.eq("severity", severity.toUpperCase(Locale.ROOT));
        if (ruleId != null && !ruleId.isBlank()) query.eq("rule_id", ruleId.toUpperCase(Locale.ROOT));
        if (pagePath != null && !pagePath.isBlank()) query.eq("page_path", pagePath);
        if (status != null && !status.isBlank()) query.eq("status", status.toUpperCase(Locale.ROOT));
        if (source != null && !source.isBlank()) query.eq("source", source.toUpperCase(Locale.ROOT));
        if ("review".equalsIgnoreCase(sort)) {
            query.orderByAsc("FIELD(status,'NEEDS_REVIEW','CONFIRMED','PENDING','DISMISSED')");
        } else {
            query.orderByAsc("FIELD(severity,'HIGH','MEDIUM','LOW')");
        }
        query.orderByAsc("rule_id", "page_path");
        long total = findings.selectCount(query);
        query.last("LIMIT " + Math.max(1, Math.min(size, 100)) + " OFFSET " + Math.max(0, page) * Math.max(1, Math.min(size, 100)));
        return new FindingsPage(findings.selectList(query), total, page, size);
    }

    public FindingDetail finding(String id) {
        FindingEntity finding = findings.selectById(id);
        if (finding == null) throw ApiException.notFound("问题不存在");
        List<EvidenceEntity> evidence = evidences.selectList(new QueryWrapper<EvidenceEntity>()
                .eq("finding_id", id).orderByAsc("created_at"));
        List<FindingReviewEntity> history = reviews.selectList(new QueryWrapper<FindingReviewEntity>()
                .eq("finding_id", id).orderByAsc("created_at"));
        List<ReviewPolicyEntity> policies = history.stream().filter(item -> item.sourcePolicyId != null)
                .map(item -> reviewPolicies.selectById(item.sourcePolicyId)).filter(Objects::nonNull).toList();
        return new FindingDetail(finding, evidence, history, policies,
                "R009".equals(finding.ruleId)
                        ? List.of("CONFIRM_RISK", "DISMISS_FALSE_POSITIVE", "NEEDS_FOLLOW_UP")
                        : List.of("generate_fix", "mark_reviewed", "ignore"));
    }

    public List<AnalysisTraceView> analysisTraces(String scanId, String type, String pagePath) {
        get(scanId);
        QueryWrapper<AnalysisTraceEntity> query = new QueryWrapper<AnalysisTraceEntity>().eq("scan_task_id", scanId);
        if (type != null && !type.isBlank()) {
            String normalized = type.toUpperCase(Locale.ROOT);
            if (!Set.of("KEYBOARD_FOCUS", "READING_ORDER").contains(normalized))
                throw ApiException.badRequest("INVALID_TRACE_TYPE", "轨迹类型仅支持 KEYBOARD_FOCUS 或 READING_ORDER");
            query.eq("trace_type", normalized);
        }
        if (pagePath != null && !pagePath.isBlank()) query.eq("page_path", pagePath);
        return traces.selectList(query.orderByAsc("page_path", "trace_type")).stream()
                .map(item -> new AnalysisTraceView(item.id, item.scanTaskId, item.pagePath, item.traceType,
                        item.status, readJson(item.payload), item.artifactId, item.createdAt)).toList();
    }

    public List<ScanLogEntity> logs(String scanId) {
        get(scanId);
        return logs.selectList(new QueryWrapper<ScanLogEntity>().eq("scan_task_id", scanId).orderByAsc("created_at"));
    }

    void executePending(String id){
        var config=readJson(get(id).scanConfig);
        List<String> pages=config.get("pages") instanceof List<?> values?values.stream().map(Object::toString).toList():List.of();
        List<String> rules=config.get("rules") instanceof List<?> values?values.stream().map(Object::toString).toList():ALL_RULES;
        execute(id,new ScanRequest(pages,Boolean.TRUE.equals(config.get("enableAi")),Boolean.TRUE.equals(config.get("aiConsent")),Boolean.TRUE.equals(config.get("enableKeyboardTest")),rules));
    }
    private boolean cancelled(String id){
        if(cancellation.contains(id))return true;
        if(jdbc==null)return false;
        var task=get(id);if(Boolean.TRUE.equals(task.cancelRequested))return true;
        try{if(access!=null)access.requireActor(task.projectId,task.createdBy);}catch(ApiException denied){jdbc.update("UPDATE scan_tasks SET cancel_requested=TRUE WHERE id=?",id);return true;}
        return false;
    }
    private void execute(String scanId, ScanRequest request) {
        ScanTaskEntity task = get(scanId);
        try {
            if(jdbc!=null&&jdbc.update("UPDATE scan_tasks SET status='RUNNING' WHERE id=? AND status='PENDING' AND cancel_requested=FALSE",scanId)!=1)return;
            if(access!=null&&jdbc!=null)access.requireActor(task.projectId,task.createdBy);
            update(task, "RUNNING", "FILE_CHECK", 5, null);
            task.startedAt = LocalDateTime.now(); scans.updateById(task);
            ProjectVersionEntity version = projects.version(task.versionId);
            update(task, "RUNNING", "PAGE_START", 15, null);
            List<String> pages = request.pages() == null ? List.of() : request.pages();
            Set<String> enabled = new HashSet<>(request.rules() == null || request.rules().isEmpty() ? ALL_RULES : request.rules());
            boolean collectKeyboard = request.enableKeyboardTest() && enabled.contains("R007");
            boolean collectReadingOrder = enabled.contains("R009");
            BrowserScanner.ScanOutput output = scanner.scan(version, pages, scanId, collectKeyboard,
                    collectReadingOrder, () -> cancelled(scanId));
            if (output.cancelled()) {
                markPatchRetestIncomplete(task, "CANCELLED");
                update(task, "CANCELLED", "AGGREGATION", task.progress, "用户取消扫描");
                return;
            }
            Map<String,Object> recordedConfig = new LinkedHashMap<>(readJson(task.scanConfig));
            recordedConfig.put("resolvedPages", output.pages().stream().map(BrowserScanner.PageOutput::pagePath).toList());
            task.scanConfig = writeJson(recordedConfig);
            scans.updateById(task);
            update(task, "RUNNING", "RULE_CHECK", 65, null);
            if (!request.enableKeyboardTest()) enabled.remove("R007");
            boolean runAi = request.enableAi() && aiAnalysis.available();
            if (request.enableAi() && !runAi) log(task, "WARN", "AI 分析未执行：" + aiAnalysis.unavailableReason());
            List<AiCandidate> aiCandidates = new ArrayList<>();
            for (BrowserScanner.PageOutput page : output.pages()) {
                aiCandidates.addAll(persistPage(task, version, page, enabled, runAi));
            }
            output.pageErrors().forEach(error -> log(task, "WARN", "页面扫描失败：" + error));
            if (runAi && !aiCandidates.isEmpty()) {
                update(task, "RUNNING", "AI_ANALYSIS", 78, null);
                analyzeCandidates(task, aiCandidates);
            }
            if(cancelled(scanId)){markPatchRetestIncomplete(task,"CANCELLED");update(task,"CANCELLED","AGGREGATION",task.progress,"扫描已取消或执行权限已失效");return;}
            update(task, "RUNNING", "SCORING", 90, null);
            recount(task);
            if (output.pageErrors().isEmpty()) verifyAppliedPatches(task);
            else markPatchRetestIncomplete(task, "PARTIAL");
            String status = output.pageErrors().isEmpty() ? "COMPLETED" : "PARTIAL";
            update(task, status, "AGGREGATION", 100,
                    output.pageErrors().isEmpty() ? null : String.join("；", output.pageErrors()));
        } catch (Exception ex) {
            log(task, "ERROR", "扫描失败：" + ex.getMessage());
            markPatchRetestIncomplete(task, "FAILED");
            update(task, "FAILED", task.stage == null ? "FILE_CHECK" : task.stage, task.progress == null ? 0 : task.progress,
                    ex.getMessage());
        } finally {
            cancellation.remove(scanId);
        }
    }

    private List<AiCandidate> persistPage(ScanTaskEntity task, ProjectVersionEntity version, BrowserScanner.PageOutput page,
                                          Set<String> enabled, boolean runAi) throws Exception {
        List<AiCandidate> aiCandidates = new ArrayList<>();
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.id = Ulids.next(); artifact.projectId = task.projectId; artifact.versionId = version.id;
        artifact.scanTaskId = task.id; artifact.kind = "SCREENSHOT";
        artifact.relativePath = storage.relativeToRoot(page.screenshot());
        artifact.sha256 = Hashes.sha256(page.screenshot()); artifact.sizeBytes = Files.size(page.screenshot());
        artifact.createdAt = LocalDateTime.now(); artifacts.insert(artifact);

        Map<String, String> traceIds = new HashMap<>();
        persistTrace(task, page.pagePath(), "KEYBOARD_FOCUS", page.focusTrace(), artifact.id, traceIds);
        persistTrace(task, page.pagePath(), "READING_ORDER", page.readingTrace(), artifact.id, traceIds);

        for (BrowserScanner.CollectedFinding item : page.findings()) {
            if (!enabled.contains(item.ruleId())) continue;
            String normalizedSelector = normalizeSelector(item.selector());
            String fingerprint = Hashes.sha256(item.ruleId() + "|" + page.pagePath() + "|" + normalizedSelector);
            Map<String, Object> evidencePayload = new LinkedHashMap<>(item.evidence());
            String traceType = String.valueOf(evidencePayload.getOrDefault("traceType", ""));
            if (traceIds.containsKey(traceType)) evidencePayload.put("traceId", traceIds.get(traceType));
            String evidenceFingerprint = Hashes.sha256(item.ruleId() + "|" + page.pagePath() + "|"
                    + normalizedSelector + "|" + canonicalEvidence(evidencePayload));
            FindingEntity finding = new FindingEntity();
            finding.id = Ulids.next(); finding.scanTaskId = task.id; finding.ruleId = item.ruleId();
            finding.source = "R009".equals(item.ruleId()) ? "HEURISTIC" : "RULE";
            finding.severity = item.severity(); finding.status = "R009".equals(item.ruleId()) ? "NEEDS_REVIEW" : "PENDING";
            finding.title = item.title(); finding.description = item.description(); finding.pagePath = page.pagePath();
            finding.selector = item.selector(); finding.fixMode = item.fixMode(); finding.fingerprint = fingerprint;
            finding.evidenceFingerprint = evidenceFingerprint;
            ReviewPolicyEntity reusedPolicy = matchingPolicy(task.projectId, item.ruleId(), page.pagePath(),
                    normalizedSelector, evidenceFingerprint);
            if (reusedPolicy != null) finding.status = "CONFIRM_RISK".equals(reusedPolicy.decision)
                    ? "CONFIRMED" : "DISMISSED";
            finding.createdAt = LocalDateTime.now();
            try { findings.insert(finding); } catch (Exception duplicate) { continue; }
            EvidenceEntity evidence = new EvidenceEntity();
            evidence.id = Ulids.next(); evidence.findingId = finding.id; evidence.kind = evidenceKind(item.ruleId());
            evidence.summary = item.description(); evidence.payload = writeJson(evidencePayload);
            evidence.artifactId = artifact.id; evidence.createdAt = LocalDateTime.now(); evidences.insert(evidence);
            if (reusedPolicy != null) saveReusedReview(finding, reusedPolicy);
            if (runAi && aiAnalysis.supports(item.ruleId())) {
                aiCandidates.add(new AiCandidate(finding, evidencePayload, artifact));
            }
        }
        return aiCandidates;
    }

    private void analyzeCandidates(ScanTaskEntity task, List<AiCandidate> candidates) {
        int created = 0;
        int failures = 0;
        for (AiCandidate candidate : candidates) {
            if(cancelled(task.id))break;
            AiAnalysisService.AnalysisResult result = aiAnalysis.analyze(
                    candidate.finding(), candidate.evidence(), candidate.screenshot());
            if (result.created()) created++;
            if (result.failure() != null) {
                failures++;
                log(task, "WARN", "AI 分析未完成（" + candidate.finding().selector + "）：" + result.failure());
            }
        }
        log(task, "INFO", "AI 语义分析完成：生成 " + created + " 条建议"
                + (failures == 0 ? "" : "，" + failures + " 条为未自动确认候选"));
    }

    private void persistTrace(ScanTaskEntity task, String pagePath, String type, Map<String, Object> payload,
                              String artifactId, Map<String, String> traceIds) {
        if (payload == null || "DISABLED".equals(String.valueOf(payload.get("status")))) return;
        AnalysisTraceEntity trace = new AnalysisTraceEntity();
        trace.id = Ulids.next();
        trace.scanTaskId = task.id;
        trace.pagePath = pagePath;
        trace.traceType = type;
        trace.status = String.valueOf(payload.getOrDefault("status", "COMPLETE"));
        trace.payload = writeJson(payload);
        trace.artifactId = artifactId;
        trace.createdAt = LocalDateTime.now();
        traces.insert(trace);
        traceIds.put(type, trace.id);
    }

    private String canonicalEvidence(Map<String, Object> payload) {
        Map<String, Object> copy = new TreeMap<>();
        payload.forEach((key, value) -> {
            if (!Set.of("traceId", "generatedAt", "createdAt").contains(key)) copy.put(key, value);
        });
        return writeJson(copy);
    }

    private ReviewPolicyEntity matchingPolicy(String projectId, String ruleId, String pagePath,
                                               String normalizedSelector, String evidenceFingerprint) {
        String matchKey = reviewMatchKey(projectId, ruleId, pagePath, normalizedSelector, evidenceFingerprint);
        return reviewPolicies.selectOne(new QueryWrapper<ReviewPolicyEntity>()
                .eq("project_id", projectId).eq("match_key", matchKey).eq("active", true).last("LIMIT 1"));
    }

    static String reviewMatchKey(String projectId, String ruleId, String pagePath,
                                 String normalizedSelector, String evidenceFingerprint) {
        return Hashes.sha256(projectId + "|" + ruleId + "|" + pagePath + "|"
                + normalizedSelector + "|" + evidenceFingerprint);
    }

    static String normalizeSelector(String selector) {
        return selector == null ? "" : selector.replaceAll(":nth-of-type\\(\\d+\\)", ":nth-of-type(*)");
    }

    private void saveReusedReview(FindingEntity finding, ReviewPolicyEntity policy) {
        FindingReviewEntity review = new FindingReviewEntity();
        review.id = Ulids.next();
        review.findingId = finding.id;
        review.action = policy.decision;
        review.comment = policy.comment;
        review.reused = true;
        review.sourcePolicyId = policy.id;
        review.createdAt = LocalDateTime.now();
        reviews.insert(review);
    }

    private String evidenceKind(String ruleId) {
        return switch (ruleId) {
            case "R006" -> "CSS";
            case "R007" -> "KEYBOARD";
            case "R009" -> "READING_ORDER";
            default -> "DOM";
        };
    }

    public ScanTaskEntity recountById(String scanId) {
        ScanTaskEntity task = get(scanId);
        recount(task);
        return task;
    }

    private void recount(ScanTaskEntity task) {
        task.highCount = countActionable(task.id, "HIGH");
        task.mediumCount = countActionable(task.id, "MEDIUM");
        task.lowCount = countActionable(task.id, "LOW");
        task.reviewCount = Math.toIntExact(findings.selectCount(new QueryWrapper<FindingEntity>()
                .eq("scan_task_id", task.id).eq("status", "NEEDS_REVIEW")));
        task.foundCount = Math.toIntExact(findings.selectCount(new QueryWrapper<FindingEntity>()
                .eq("scan_task_id", task.id).ne("status", "DISMISSED")));
        task.riskScore = Math.max(0, 100 - 10 * task.highCount - 4 * task.mediumCount - task.lowCount);
        scans.updateById(task);
    }

    private int countActionable(String scanId, String severity) {
        return Math.toIntExact(findings.selectCount(new QueryWrapper<FindingEntity>()
                .eq("scan_task_id", scanId).eq("severity", severity)
                .notIn("status", "NEEDS_REVIEW", "DISMISSED")));
    }

    private void verifyAppliedPatches(ScanTaskEntity task) {
        List<FixPatchEntity> applied = patches.selectList(new QueryWrapper<FixPatchEntity>()
                .eq("new_version_id", task.versionId).eq("status", "APPLIED"));
        for (FixPatchEntity patch : applied) {
            FindingEntity original = findings.selectById(patch.findingId);
            long remaining = findings.selectCount(new QueryWrapper<FindingEntity>()
                    .eq("scan_task_id", task.id).eq("rule_id", original.ruleId)
                    .eq("page_path", original.pagePath).eq("selector", original.selector));
            boolean cleared = remaining == 0;
            patch.status = cleared ? "VERIFIED" : "FAILED";
            patch.verification = writeJson(Map.of(
                    "syntax", "passed",
                    "result", remaining == 0 ? "FIXED" : "FAILED",
                    "ruleCleared", cleared,
                    "validationLevel", "RULE",
                    "semanticReview", "AI_CANDIDATE".equals(patch.patchType) ? "HUMAN_ACCEPTED" : "NOT_REQUIRED",
                    "aiReanalysis", false,
                    "retestStatus", "COMPLETED",
                    "rescanId", task.id,
                    "remainingMatches", remaining,
                    "checkedAt", LocalDateTime.now().toString()));
            patch.updatedAt = LocalDateTime.now();
            patches.updateById(patch);
        }
    }

    private void markPatchRetestIncomplete(ScanTaskEntity task, String status) {
        List<FixPatchEntity> applied = patches.selectList(new QueryWrapper<FixPatchEntity>()
                .eq("new_version_id", task.versionId).eq("status", "APPLIED"));
        for (FixPatchEntity patch : applied) {
            patch.verification = writeJson(Map.of(
                    "syntax", "passed",
                    "ruleCleared", false,
                    "validationLevel", "RULE",
                    "semanticReview", "AI_CANDIDATE".equals(patch.patchType) ? "HUMAN_ACCEPTED" : "NOT_REQUIRED",
                    "aiReanalysis", false,
                    "retestStatus", status,
                    "rescanId", task.id,
                    "checkedAt", LocalDateTime.now().toString()));
            patch.updatedAt = LocalDateTime.now();
            patches.updateById(patch);
        }
    }

    private void update(ScanTaskEntity task, String status, String stage, int progress, String error) {
        task.status = status; task.stage = stage; task.progress = progress; task.errorMessage = error;
        if (Set.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED").contains(status)) task.finishedAt = LocalDateTime.now();
        scans.updateById(task); log(task, error == null ? "INFO" : "WARN", stage + " · " + status);
        events.publish(task);
    }

    private void log(ScanTaskEntity task, String level, String message) {
        ScanLogEntity item = new ScanLogEntity(); item.id = Ulids.next(); item.scanTaskId = task.id;
        item.level = level; item.stage = task.stage; item.message = message; item.details = "{}";
        item.createdAt = LocalDateTime.now(); logs.insert(item);
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法序列化扫描数据", e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String value) {
        try { return json.readValue(value, Map.class); }
        catch (Exception e) { return Map.of("parseError", "轨迹数据无法解析"); }
    }

    private record AiCandidate(FindingEntity finding, Map<String, Object> evidence, ArtifactEntity screenshot) {}

    public record ScanRequest(List<String> pages, Boolean enableAi, Boolean aiConsent,
                              Boolean enableKeyboardTest, List<String> rules) {
        public ScanRequest {
            enableAi = Boolean.TRUE.equals(enableAi);
            aiConsent = Boolean.TRUE.equals(aiConsent);
            enableKeyboardTest = enableKeyboardTest == null || enableKeyboardTest;
        }
    }
    public record FindingsPage(List<FindingEntity> items, long total, int page, int size) {}
    public record FindingDetail(FindingEntity finding, List<EvidenceEntity> evidences,
                                List<FindingReviewEntity> reviews, List<ReviewPolicyEntity> reusedPolicies,
                                List<String> availableActions) {}
    public record AnalysisTraceView(String id, String scanTaskId, String pagePath, String traceType,
                                    String status, Map<String, Object> payload, String artifactId,
                                    LocalDateTime createdAt) {}
}
