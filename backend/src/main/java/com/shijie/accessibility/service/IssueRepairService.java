package com.shijie.accessibility.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.shijie.accessibility.ai.AiProvider;
import com.shijie.accessibility.common.*;
import com.shijie.accessibility.mapper.IssueRepairMapper;
import com.shijie.accessibility.mapper.ProjectVersionMapper;
import com.shijie.accessibility.model.*;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Frozen single-file patches are applied to a separate, immutable source version. */
@Service
public class IssueRepairService {
    static final int MAX_EDIT_BYTES = 1024 * 1024;
    private final CollaborationService collaboration;
    private final ProjectAccessService access;
    private final ProjectService projects;
    private final ProjectVersionMapper versions;
    private final IssueRepairMapper repairs;
    private final StorageService storage;
    private final FixService templates;
    private final SourcePatchService attributes;
    private final AiProvider ai;
    private final ScanService scans;
    private final TestRunService runs;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public IssueRepairService(CollaborationService collaboration, ProjectAccessService access, ProjectService projects,
                              ProjectVersionMapper versions, IssueRepairMapper repairs, StorageService storage,
                              FixService templates, SourcePatchService attributes, AiProvider ai, ScanService scans,
                              TestRunService runs, IdempotencyService idempotency, JdbcTemplate jdbc, ObjectMapper json) {
        this.collaboration=collaboration; this.access=access; this.projects=projects; this.versions=versions;
        this.repairs=repairs; this.storage=storage; this.templates=templates; this.attributes=attributes;
        this.ai=ai; this.scans=scans; this.runs=runs; this.idempotency=idempotency; this.jdbc=jdbc; this.json=json;
    }

    public Workspace workspace(String issueId) {
        var issue = issue(issueId, "VIEWER");
        var history = repairs.selectList(new QueryWrapper<IssueRepairEntity>().eq("issue_id", issueId)
                .orderByDesc("created_at").last("LIMIT 30")).stream()
                .map(r -> new RepairSummary(r.id, r.status, r.targetFile, r.mode, r.createdAt)).toList();
        var hosted=jdbc.queryForList("SELECT d.site_id,d.source_version_id FROM hosted_deployments d JOIN test_runs t ON t.target_id=d.target_id WHERE t.id=?",issue.get("runId"));
        return new Workspace(issue, projects.versions(projectId(issue)), history, ai.enabled(), ai.unavailableReason(),hosted.isEmpty()?null:(String)hosted.getFirst().get("site_id"),hosted.isEmpty()?null:(String)hosted.getFirst().get("source_version_id"));
    }

    @Transactional
    public ProjectVersionEntity upload(String issueId, MultipartFile file) {
        var issue = issue(issueId, "MEMBER");
        String projectId=projectId(issue);
        lockProject(projectId);
        String id=Ulids.next();
        var imported=storage.importRepairUpload(file, projectId, id);
        var version=newVersion(projectId, id, null, imported.entryPage(), imported.manifestHash(), "REPAIR_SOURCE");
        versions.insert(version);
        history(issue, "REPAIR_SOURCE_UPLOADED", Map.of("versionId", id));
        return version;
    }

    public List<String> files(String issueId, String versionId) {
        var version=sourceVersion(issue(issueId, "VIEWER"), versionId);
        try { return storage.listRelativeFiles(storage.versionSource(version.projectId, version.id)).stream()
                .filter(StorageService::editableSource).toList(); }
        catch(IOException ex) { throw ApiException.badRequest("SOURCE_UNREADABLE", "无法读取源码包"); }
    }

    public SourceFile file(String issueId, String versionId, String path) {
        var issue=issue(issueId, "VIEWER");
        var version=sourceVersion(issue, versionId);
        return readFile(issue, version, path);
    }

    // Do not hold a database lock while waiting on the external model.
    public AiProvider.RepairSuggestion candidate(String issueId, CandidateRequest request) {
        var issue=issue(issueId, "MEMBER");
        var file=readFile(issue, sourceVersion(issue, request.versionId()), request.path());
        checkHash(file, request.beforeSha());
        if (!request.aiConsent()) throw ApiException.badRequest("AI_CONSENT_REQUIRED", "请确认将选中文件与脱敏问题发送给 AI 修复服务");
        if (!ai.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", ai.unavailableReason());
        if (file.content().length()>40_000) throw ApiException.badRequest("AI_SOURCE_TOO_LARGE", "AI 修复支持 4 万字以内的单个文件，请选择更小的文件或手动编辑");
        if (!SensitiveText.redact(file.content(), Integer.MAX_VALUE).equals(file.content().strip()))
            throw ApiException.badRequest("AI_SOURCE_SENSITIVE", "文件可能包含密钥或会话信息，请先移除敏感值，或使用本地编辑修复");
        AiProvider.RepairSuggestion candidate;
        try {
            candidate=ai.generateRepair(new AiProvider.RepairRequest(SensitiveText.redact(issue.get("title"), 500),
                    SensitiveText.redact(issue.get("description"), 1500), SensitiveText.redactValues(issue.get("evidence")),
                    file.path(), file.content())).orElseThrow(() -> new IllegalStateException("未返回代码"));
        } catch (Exception ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_REPAIR_FAILED", "AI 修复未完成，请重试或编辑源码。原文件仍已保留。"); }
        validateContent(file.content(), candidate.content());
        return candidate;
    }

    @Transactional
    public RepairDetail generate(String issueId, GenerateRequest request, String key) {
        var issue=issue(issueId, "MEMBER");
        lockProject(projectId(issue));
        var previous=idempotency.find(key, "generate-issue-repair");
        if (previous.isPresent()) {
            var existing=required(previous.get());
            if (!existing.issueId.equals(issueId)) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "该请求已用于另一问题");
            return detail(existing);
        }
        var version=sourceVersion(issue, request.versionId());
        var file=readFile(issue, version, request.path());
        checkHash(file, request.beforeSha());
        String mode=request.mode()==null?"":request.mode().toUpperCase(Locale.ROOT);
        String revised;
        Map<String,String> explanation;
        if ("TEMPLATE".equals(mode)) {
            revised=template(file, issue, request);
            explanation=templateExplanation(file.templateRule());
        } else if (Set.of("AI", "MANUAL").contains(mode)) {
            revised=request.content();
            explanation=new LinkedHashMap<>();
            explanation.put("summary", note(request.summary(), "修改选定文件，详见前后差异"));
            explanation.put("reason", note(request.reason(), "根据问题证据修改源码，实际原因请结合业务核对"));
            explanation.put("benefit", note(request.benefit(), "预期改善需要复测验证"));
            explanation.put("prevention", note(request.prevention(), "将此场景加入回归检查，发布前再次执行"));
        } else throw ApiException.badRequest("INVALID_REPAIR_MODE", "请选择自动补丁或编辑源码");
        validateContent(file.content(), revised);
        var originalLines=Arrays.asList(file.content().split("\n", -1));
        var revisedLines=Arrays.asList(revised.split("\n", -1));
        var repair=new IssueRepairEntity();
        repair.id=Ulids.next(); repair.issueId=issueId; repair.baseVersionId=version.id;
        repair.targetFile=file.path(); repair.beforeSha=file.sha(); repair.patchedContent=revised;
        repair.diffText=String.join("\n", UnifiedDiffUtils.generateUnifiedDiff(file.path(), file.path(), originalLines,
                DiffUtils.diff(originalLines, revisedLines), 3));
        repair.mode=mode; repair.status="PENDING"; repair.explanation=write(explanation);
        repair.createdBy=access.currentUser().id(); repair.createdAt=LocalDateTime.now(); repair.updatedAt=repair.createdAt;
        repairs.insert(repair);
        idempotency.save(key, "generate-issue-repair", repair.id);
        history(issue, "REPAIR_PREPARED", Map.of("repairId", repair.id, "file", repair.targetFile, "mode", mode));
        return detail(repair);
    }

    public RepairDetail get(String id) {
        var repair=required(id); issue(repair.issueId, "VIEWER"); return detail(repair);
    }

    @Transactional
    public RepairDetail apply(String id, boolean accepted) throws IOException {
        var repair=required(id);
        var issue=issue(repair.issueId, "MEMBER");
        lockProject(projectId(issue));
        lockRepair(id); repair=required(id);
        if (Set.of("APPLIED", "VERIFIED").contains(repair.status)) return detail(repair);
        if (!accepted) throw ApiException.badRequest("REPAIR_REVIEW_REQUIRED", "请先确认已检查前后差异");
        if (!"PENDING".equals(repair.status)) throw ApiException.conflict("REPAIR_NOT_PENDING", "补丁不能再次应用");
        var base=sourceVersion(issue, repair.baseVersionId);
        var source=storage.versionSource(base.projectId, base.id);
        if (!base.manifestHash.equals(storage.manifestHash(source)) || !repair.beforeSha.equals(Hashes.sha256(storage.resolveVersionFile(base.projectId, base.id, repair.targetFile))))
            throw ApiException.conflict("SOURCE_CHANGED", "原始源码已变化，请重新生成补丁");
        String versionId=Ulids.next();
        var destination=storage.versionSource(base.projectId, versionId);
        storage.copyTree(source, destination);
        Files.writeString(destination.resolve(repair.targetFile), repair.patchedContent, StandardCharsets.UTF_8);
        var version=newVersion(base.projectId, versionId, base.id, base.entryPage, storage.manifestHash(destination), "REPAIR_PATCH");
        versions.insert(version);
        repair.newVersionId=versionId; repair.status="APPLIED"; repair.updatedAt=LocalDateTime.now(); repair.appliedAt=repair.updatedAt;
        if (html(version.entryPage)) repair.staticScanId=scans.createForVersion(version,
                new ScanService.ScanRequest(List.of(version.entryPage), false, false, true, List.of())).id;
        repairs.updateById(repair);
        // Code delivery does not assert that the live target has changed.
        if (!"VERIFIED".equals(issue.get("taskStatus")))
            jdbc.update("UPDATE run_issues SET task_status='READY_FOR_RETEST',updated_at=? WHERE id=?", repair.updatedAt, repair.issueId);
        history(issue, "REPAIR_APPLIED", Map.of("repairId", id, "versionId", versionId, "file", repair.targetFile));
        return detail(repair);
    }

    public void download(String id, OutputStream output) throws IOException {
        var repair=required(id);
        var issue=issue(repair.issueId, "VIEWER");
        if (repair.newVersionId==null) throw ApiException.conflict("REPAIR_NOT_APPLIED", "请先应用补丁，再下载修复后的源码");
        var version=sourceVersion(issue, repair.newVersionId);
        var root=storage.versionSource(version.projectId, version.id);
        var files=storage.listRelativeFiles(root);
        if (files.size()>StorageService.MAX_FILES) throw ApiException.conflict("SOURCE_FILE_LIMIT", "源码包文件过多");
        long total=0;
        // Validate the whole package before starting the response body.
        for (String path:files) total+=Files.size(storage.resolveVersionFile(version.projectId, version.id, path));
        if (total>StorageService.MAX_EXPANDED_BYTES) throw ApiException.conflict("SOURCE_SIZE_LIMIT", "源码包超过大小限制");
        try (var zip=new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (String path:files) {
                zip.putNextEntry(new ZipEntry(path));
                Files.copy(storage.resolveVersionFile(version.projectId, version.id, path), zip);
                zip.closeEntry();
            }
        }
    }

    @Transactional
    public RepairDetail retest(String id, boolean deployed, String key) {
        var repair=required(id);
        var issue=issue(repair.issueId, "MEMBER");
        lockRepair(id); repair=required(id);
        if (repair.newVersionId==null || !deployed) throw ApiException.badRequest("DEPLOYMENT_REQUIRED", "请先将修复代码部署到原检测目标，再启动在线复测");
        if (repair.retestRunId!=null && Set.of("PENDING", "RUNNING").contains(runs.get(repair.retestRunId).status())) return detail(repair);
        var original=runs.get(String.valueOf(issue.get("runId")));
        String module=jdbc.queryForObject("SELECT module_type FROM test_run_modules WHERE id=?", String.class, issue.get("moduleId"));
        if (!Set.of("FRONTEND", "SECURITY").contains(module)) throw ApiException.badRequest("REPAIR_RETEST_SCOPE", "承载能力问题请在原运行页按已授权的压力参数重新执行，再关联结果");
        var run=runs.createRepairRetest(original.projectId(), original.targetId(), original.suiteVersionId(), module, key);
        repair.retestRunId=run.id(); repair.updatedAt=LocalDateTime.now(); repairs.updateById(repair);
        history(issue, "REPAIR_RETEST_STARTED", Map.of("repairId", id, "runId", run.id()));
        return detail(repair);
    }

    @Transactional
    public RepairDetail linkRetest(String id, String runId) {
        var repair=required(id);
        var issue=issue(repair.issueId, "MEMBER");
        lockRepair(id); repair=required(id);
        if (!"APPLIED".equals(repair.status)) throw ApiException.conflict("REPAIR_NOT_APPLIED", "请先应用补丁；已验证的修复记录不能更换复测");
        var original=runs.get(String.valueOf(issue.get("runId")));
        var retest=runs.get(runId);
        requireRetestAfterApply(repair, original, retest);
        repair.retestRunId=runId; repair.updatedAt=LocalDateTime.now(); repairs.updateById(repair);
        history(issue, "REPAIR_RETEST_LINKED", Map.of("repairId", id, "runId", runId));
        return detail(repair);
    }

    @Transactional
    public RepairDetail verify(String id, String key) {
        var repair=required(id);
        var issue=issue(repair.issueId, "MEMBER");
        lockRepair(id); repair=required(id);
        if ("VERIFIED".equals(repair.status)) return detail(repair);
        if (repair.retestRunId==null) throw ApiException.badRequest("RETEST_REQUIRED", "需要先完成在线复测");
        var original=runs.get(String.valueOf(issue.get("runId")));
        var retest=runs.get(repair.retestRunId);
        requireRetestAfterApply(repair, original, retest);
        String module=jdbc.queryForObject("SELECT module_type FROM test_run_modules WHERE id=?", String.class, issue.get("moduleId"));
        requireSameCoverage(original.configSnapshot(), retest.configSnapshot(), module, String.valueOf(issue.get("source")));
        requireUsablePageEvidence(retest);
        collaboration.updateIssue(repair.issueId, "VERIFIED", (String)issue.get("assigneeId"), repair.retestRunId, key);
        repair.status="VERIFIED"; repair.updatedAt=LocalDateTime.now(); repairs.updateById(repair);
        history(issue, "REPAIR_VERIFIED", Map.of("repairId", id, "runId", repair.retestRunId));
        return detail(repair);
    }

    void requireSameCoverage(String originalSnapshot, String retestSnapshot, String module, String source) {
        var original=readMap(originalSnapshot); var retest=readMap(retestSnapshot);
        boolean hosted=com.shijie.accessibility.hosting.HostedRunIdentity.isRetest(original,retest);
        if (Boolean.TRUE.equals(retest.get("agentSafe")) && Set.of("ZAP", "FLOW", "UPLOAD", "SCOPE", "AGENT").contains(source))
            throw ApiException.badRequest("RETEST_COVERAGE_CHANGED", "该问题需要完整执行原用例，本次只读复测不能确认修复");
        if (Boolean.TRUE.equals(original.get("activeScan")) && !Boolean.TRUE.equals(retest.get("activeScan")))
            throw ApiException.badRequest("RETEST_COVERAGE_CHANGED", "原运行包含主动检查，请完整执行原用例后关联结果");
        for (String field:hosted?List.of("pathPrefixes"):List.of("baseUrl", "allowedOrigin", "allowedAddresses", "pathPrefixes"))
            if (!Objects.equals(asMap(original.get("target")).get(field), asMap(retest.get("target")).get(field)))
                throw ApiException.badRequest("RETEST_TARGET_CHANGED", "复测的目标范围与原运行不同，不能确认此问题已修复");
        var originalSuite=asMap(asMap(original.get("suite")).get("definition"));
        var retestSuite=asMap(asMap(retest.get("suite")).get("definition"));
        var keys="FRONTEND".equals(module)?List.of("pages", "flowSteps"):"PERFORMANCE".equals(module)?List.of("performance"):List.of("apiCases");
        for (String field:keys) if (!Objects.equals(originalSuite.getOrDefault(field, List.of()), retestSuite.getOrDefault(field, List.of())))
            throw ApiException.badRequest("RETEST_COVERAGE_CHANGED", "复测未覆盖原运行的全部用例，请在运行页执行完整复测后关联结果");
    }

    private void requireRetestAfterApply(IssueRepairEntity repair, TestRunService.RunView original, TestRunService.RunView retest) {
        boolean hosted=com.shijie.accessibility.hosting.HostedRunIdentity.isRetest(readMap(original.configSnapshot()),readMap(retest.configSnapshot()));
        if (!original.projectId().equals(retest.projectId()) || !hosted&&!original.targetId().equals(retest.targetId()) || !original.suiteVersionId().equals(retest.suiteVersionId()))
            throw ApiException.badRequest("RETEST_SCOPE_MISMATCH", "复测必须使用原项目、目标和方案版本");
        if (repair.appliedAt==null || retest.createdAt().isBefore(repair.appliedAt))
            throw ApiException.badRequest("RETEST_BEFORE_REPAIR", "请选择应用本次修复之后创建的复测运行");
    }

    void requireUsablePageEvidence(TestRunService.RunView retest) {
        for (var issue:retest.issues()) {
            if (!"BROWSER".equals(issue.source())) continue;
            var evidence=readMap(issue.evidence());
            var response=asMap(evidence.get("response"));
            if (response.get("status") instanceof Number status && status.intValue()>=400
                    && Objects.equals(evidence.get("pageUrl"), response.get("url")))
                throw ApiException.badRequest("RETEST_PAGE_UNAVAILABLE", "复测页面返回错误状态，不能据此确认原问题已修复");
        }
    }

    private SourceFile readFile(Map<String,Object> issue, ProjectVersionEntity version, String path) {
        if (path==null || !StorageService.editableSource(path)) throw ApiException.badRequest("SOURCE_NOT_EDITABLE", "请选择可编辑的源码文件");
        var file=storage.resolveVersionFile(version.projectId, version.id, path);
        try {
            if (Files.size(file)>MAX_EDIT_BYTES) throw ApiException.badRequest("SOURCE_TOO_LARGE", "单个修复文件不能超过 1 MB");
            String content=Files.readString(file, StandardCharsets.UTF_8);
            if (content.indexOf('\0')>=0) throw ApiException.badRequest("SOURCE_BINARY", "请选择 UTF-8 文本源码");
            String rule=String.valueOf(finding(issue).getOrDefault("ruleId", ""));
            String templateRule=html(path) && Set.of("R001", "R002", "R003", "R004", "R007").contains(rule)?rule:null;
            return new SourceFile(path.replace('\\','/'), content, Hashes.sha256(file), templateRule);
        } catch(IOException ex) { throw ApiException.badRequest("SOURCE_ENCODING", "无法读取该文件，请使用 UTF-8 文本源码"); }
    }

    private String template(SourceFile file, Map<String,Object> issue, GenerateRequest request) {
        if (file.templateRule()==null) throw ApiException.badRequest("TEMPLATE_UNAVAILABLE", "此问题暂无自动模板，请使用 AI 修复或编辑源码");
        String selector=String.valueOf(finding(issue).getOrDefault("selector", ""));
        return switch(file.templateRule()) {
            case "R004" -> templates.patchLanguage(file.content(), request.language()==null?"zh-CN":request.language());
            case "R002" -> templates.patchLabel(file.content(), selector);
            case "R007" -> templates.patchFocus(file.content());
            case "R001", "R003" -> attributes.patchAiAttribute(file.content(), selector, file.templateRule(),
                    request.candidateText(), request.decorative()).content();
            default -> throw ApiException.badRequest("TEMPLATE_UNAVAILABLE", "没有适用模板");
        };
    }

    static Map<String,String> templateExplanation(String rule) {
        return switch(rule) {
            case "R004" -> Map.of("summary", "给页面补充正确的语言属性", "reason", "辅助阅读工具需要识别页面语言", "benefit", "帮助屏幕阅读器使用合适的发音", "prevention", "在页面模板中统一设置语言，并在发布前检查");
            case "R002" -> Map.of("summary", "把已有标签与对应输入框关联", "reason", "输入框缺少可识别的标签关系", "benefit", "帮助用户理解输入内容，也能点击标签聚焦", "prevention", "表单组件统一使用对应的 id 与 for，并加入组件检查");
            case "R007" -> Map.of("summary", "补充清晰可见的键盘焦点样式", "reason", "键盘用户需要知道当前操作位置", "benefit", "改善键盘导航与控件定位", "prevention", "保留公共焦点样式，发布前用 Tab 检查关键操作");
            default -> Map.of("summary", "写入已确认的图片描述或控件名称", "reason", "元素缺少可访问名称或替代文本", "benefit", "帮助辅助阅读工具理解元素用途", "prevention", "在内容与组件录入时检查名称，人工核对业务含义");
        };
    }

    static void validateContent(String before, String after) {
        if (after==null || after.isBlank() || after.indexOf('\0')>=0 || after.getBytes(StandardCharsets.UTF_8).length>MAX_EDIT_BYTES)
            throw ApiException.badRequest("REPAIR_CONTENT_INVALID", "修复代码须为非空 UTF-8 文本，且不超过 1 MB");
        if (before.equals(after)) throw ApiException.badRequest("REPAIR_NO_CHANGE", "源码没有变化，请检查选定文件是否包含此问题");
    }
    private void checkHash(SourceFile file, String hash) {
        if (!file.sha().equals(hash)) throw ApiException.conflict("SOURCE_CHANGED", "源码已变化，请重新读取后修复");
    }
    private Map<String,Object> issue(String id, String role) {
        var issue=collaboration.issue(id); access.require(projectId(issue), role); return issue;
    }
    private String projectId(Map<String,Object> issue) { return String.valueOf(issue.get("projectId")); }
    private ProjectVersionEntity sourceVersion(Map<String,Object> issue, String id) {
        var version=projects.version(id);
        if (!version.projectId.equals(projectId(issue))) throw ApiException.notFound("该源码不属于问题所在项目");
        return version;
    }
    private IssueRepairEntity required(String id) {
        var repair=repairs.selectById(id); if(repair==null) throw ApiException.notFound("修复记录不存在"); return repair;
    }
    private void lockProject(String id) { jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", String.class, id); }
    private void lockRepair(String id) { jdbc.queryForObject("SELECT id FROM issue_repairs WHERE id=? FOR UPDATE", String.class, id); }
    private ProjectVersionEntity newVersion(String projectId, String id, String base, String entry, String manifest, String type) {
        var version=new ProjectVersionEntity(); version.id=id; version.projectId=projectId; version.baseVersionId=base;
        version.versionNo=projects.versions(projectId).stream().mapToInt(v -> v.versionNo).max().orElse(0)+1;
        version.entryPage=entry; version.manifestHash=manifest; version.sourceType=type;
        version.storagePath=storage.relativeToRoot(storage.versionSource(projectId, id)); version.createdAt=LocalDateTime.now();
        return version;
    }
    private RepairDetail detail(IssueRepairEntity repair) {
        return new RepairDetail(repair.id, repair.issueId, repair.baseVersionId, repair.newVersionId, repair.targetFile,
                repair.status, repair.mode, repair.diffText, repair.patchedContent, readMap(repair.explanation), repair.createdAt,
                repair.staticScanId==null?null:scans.get(repair.staticScanId), repair.retestRunId==null?null:runs.get(repair.retestRunId),repair.hostedDeploymentError);
    }
    private void history(Map<String,Object> issue, String action, Object details) {
        jdbc.update("INSERT INTO issue_history(id,issue_id,user_id,action,from_status,to_status,details,created_at) VALUES(?,?,?,?,?,?,?,?)",
                Ulids.next(), issue.get("id"), access.currentUser().id(), action, issue.get("taskStatus"), issue.get("taskStatus"), write(details), LocalDateTime.now());
    }
    private Map<String,Object> finding(Map<String,Object> issue) { return asMap(asMap(issue.get("evidence")).get("finding")); }
    @SuppressWarnings("unchecked") private Map<String,Object> asMap(Object value) { return value instanceof Map<?,?> map?(Map<String,Object>)map:Map.of(); }
    private Map<String,Object> readMap(String value) { try { return asMap(json.readValue(value, Object.class)); } catch(Exception ex) { throw new IllegalStateException("修复记录格式错误", ex); } }
    private String write(Object value) { return json.writeValueAsString(value); }
    private String note(String value, String fallback) { return value==null || value.isBlank()?fallback:SensitiveText.redact(value, 1000); }
    private static boolean html(String path) { return path!=null && path.toLowerCase(Locale.ROOT).matches(".*\\.html?"); }

    public record Workspace(Map<String,Object> issue, List<ProjectVersionEntity> versions, List<RepairSummary> repairs, boolean aiEnabled, String aiUnavailableReason,String hostedSiteId,String deployedSourceVersionId) {}
    public record RepairSummary(String id, String status, String targetFile, String mode, LocalDateTime createdAt) {}
    public record SourceFile(String path, String content, String sha, String templateRule) {}
    public record CandidateRequest(String versionId, String path, String beforeSha, boolean aiConsent) {}
    public record GenerateRequest(String versionId, String path, String beforeSha, String mode, String content,
                                  String language, String candidateText, boolean decorative,
                                  String summary, String reason, String benefit, String prevention) {}
    public record RepairDetail(String id, String issueId, String baseVersionId, String newVersionId, String targetFile,
                               String status, String mode, String diffText, String content, Map<String,Object> explanation,
                               LocalDateTime createdAt, ScanTaskEntity staticScan, TestRunService.RunView retest,String hostedDeploymentError) {}
}
