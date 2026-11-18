package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Hashes;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.common.PerformanceAssessment;
import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.online.TargetScopeService;
import com.shijie.accessibility.runner.K6PerformanceRunner;
import com.shijie.accessibility.runner.ZapActiveScanRunner;
import com.shijie.accessibility.scanner.OnlineFrontendScanner;
import com.shijie.accessibility.scanner.OnlineSecurityScanner;
import com.shijie.accessibility.scanner.ApiSecurityChecker;
import com.shijie.accessibility.security.ProjectAccessService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TestRunService {
    @org.springframework.beans.factory.annotation.Value("${app.hosting.worker-only:false}") private boolean workerOnly;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.shijie.accessibility.hosting.HostingTargetRegistry hosting;
    private static final List<String> MODULE_ORDER = List.of("FRONTEND", "SECURITY", "PERFORMANCE");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OnlineTargetService targets;
    private final TargetScopeService targetScope;
    private final TestSuiteService suites;
    private final ProjectAccessService access;
    private final IdempotencyService idempotency;
    private final OnlineFrontendScanner frontend;
    private final OnlineSecurityScanner security;
    private final ApiSecurityChecker apiSecurity;
    private final ZapActiveScanRunner zap;
    private final K6PerformanceRunner k6;
    private final TaskExecutor executor;
    private final RunEvents events;
    private final TransactionTemplate transactions;
    private final Set<String> cancellation = ConcurrentHashMap.newKeySet();

    public TestRunService(JdbcTemplate jdbc, ObjectMapper json, OnlineTargetService targets, TargetScopeService targetScope, TestSuiteService suites,
                          ProjectAccessService access, IdempotencyService idempotency, OnlineFrontendScanner frontend,
                          OnlineSecurityScanner security, ApiSecurityChecker apiSecurity, ZapActiveScanRunner zap, K6PerformanceRunner k6,
                          @Qualifier("scanExecutor") TaskExecutor executor, RunEvents events, PlatformTransactionManager transactionManager) {
        this.jdbc=jdbc; this.json=json; this.targets=targets; this.targetScope=targetScope; this.suites=suites; this.access=access; this.idempotency=idempotency;
        this.frontend=frontend; this.security=security; this.apiSecurity=apiSecurity; this.zap=zap; this.k6=k6; this.executor=executor; this.events=events;this.transactions=new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void recoverInterrupted() {
        LocalDateTime now=LocalDateTime.now();
        String owner="COALESCE(JSON_UNQUOTE(JSON_EXTRACT(t.config_snapshot,'$.executionOwner')),'PLATFORM')";
        String scope=" AND run_id IN(SELECT t.id FROM test_runs t WHERE "+owner+(workerOnly?"='HOSTING_WORKER'":"<>'HOSTING_WORKER'")+")";
        jdbc.update("UPDATE test_run_modules SET status=CASE WHEN status='RUNNING' THEN 'FAILED' ELSE 'SKIPPED' END,summary='服务重启导致子任务中断或未执行',finished_at=? WHERE status IN ('RUNNING','PENDING')"+scope+" AND run_id IN (SELECT id FROM test_runs WHERE status='RUNNING')", now);
        jdbc.update("UPDATE test_runs t SET status='PARTIAL',error_message='服务重启导致运行中断',finished_at=? WHERE status='RUNNING' AND "+owner+(workerOnly?"='HOSTING_WORKER'":"<>'HOSTING_WORKER'"), now);
    }

    @Transactional
    public RunView create(CreateRun request, String key) {
        access.require(request.projectId(),"MEMBER");
        return createInternal(request,key,false,false,access.currentUser().id(),null,null);
    }

    /** Called only by the approved agent task worker; ordinary run semantics stay unchanged. */
    @Transactional
    public RunView createAgent(String taskId,String projectId,String targetId,String suiteVersionId,
                               String module,boolean includeFlow,String actorId,String expectedTargetUpdatedAt) {
        access.requireMutable(projectId);
        if(!MODULE_ORDER.contains(module)) throw ApiException.badRequest("INVALID_MODULE","检测模块无效");
        Integer member=jdbc.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=? AND role IN ('MEMBER','ADMIN')",
                Integer.class,projectId,actorId);
        if(member==null||member==0) throw ApiException.forbidden("任务创建者已无执行权限");
        return createInternal(new CreateRun(projectId,targetId,suiteVersionId,List.of(module),false),
                "agent:"+taskId+":"+module,true,includeFlow,actorId,taskId,expectedTargetUpdatedAt);
    }

    @Transactional
    public RunView createRepairRetest(String projectId,String targetId,String suiteVersionId,String module,String key) {
        access.require(projectId,"MEMBER");
        if(!List.of("FRONTEND","SECURITY").contains(module)) throw ApiException.badRequest("REPAIR_RETEST_SCOPE","只读修复复测支持页面和接口检查");
        var target=targets.get(projectId,targetId);
        var run=createInternal(new CreateRun(projectId,targetId,suiteVersionId,List.of(module),false),key,true,false,
                access.currentUser().id(),null,target.updatedAt().toString());
        if(!run.projectId().equals(projectId)||!run.targetId().equals(targetId)||!run.suiteVersionId().equals(suiteVersionId))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT","该复测请求已用于另一目标");
        return run;
    }

    private RunView createInternal(CreateRun request,String key,boolean agent,boolean includeFlow,String actorId,String taskId,String expectedTargetUpdatedAt) {
        String idempotencyOperation=agent?(taskId==null?"create-repair-retest":"create-agent-test-run"):"create-test-run";
        var previous=idempotency.find(key,idempotencyOperation);
        if(previous.isPresent()) return hydrate(systemGet(previous.get()));
        OnlineTargetService.TargetView target=agent?targets.systemGet(request.projectId(),request.targetId()):targets.get(request.projectId(),request.targetId());
        if(agent&&!Objects.equals(target.updatedAt().toString(),expectedTargetUpdatedAt))
            throw ApiException.conflict("AGENT_TARGET_CHANGED","目标配置在计划确认后发生变化，请重新生成计划");
        TestSuiteService.SuiteVersion version=agent?suites.systemVersion(request.suiteVersionId()):suites.version(request.suiteVersionId());
        if(!version.projectId().equals(request.projectId())) throw ApiException.badRequest("SUITE_PROJECT_MISMATCH","测试套件不属于当前项目");
        List<String> modules=normalizeModules(request.modules());
        if(agent && request.activeScan()) throw ApiException.badRequest("AGENT_ACTIVE_SCAN_FORBIDDEN","智能体不可执行主动漏洞扫描");
        if(agent && includeFlow && !target.allowWriteTests()) throw ApiException.badRequest("AGENT_FLOW_NOT_ALLOWED","目标未授权交互写测试");
        if(request.activeScan() && !target.allowActiveScan()) throw ApiException.badRequest("ACTIVE_SCAN_NOT_ALLOWED","目标尚未授权主动安全扫描");
        List<Map<String,Object>> apiCases=mapList(version.definition().get("apiCases"));
        if(hosting!=null&&hosting.snapshot(target.id()).map(s->"HOST".equals(s.get("environmentKind"))).orElse(false)&&
                (request.activeScan()||modules.contains("PERFORMANCE")||!mapList(version.definition().get("flowSteps")).isEmpty()||apiCases.stream().anyMatch(c->!Set.of("GET","HEAD","OPTIONS").contains(String.valueOf(c.getOrDefault("method","GET"))))))throw ApiException.badRequest("HOSTED_TEST_ENVIRONMENT_REQUIRED","请从网站工作区创建独立检测版本，写入、主动扫描和压力测试不能在正式数据环境执行");
        if(request.activeScan() && apiCases.stream().noneMatch(zap::eligible)) throw ApiException.badRequest("ACTIVE_SCAN_CASES_REQUIRED","主动扫描前必须登记 GET 接口、查询参数并明确启用主动扫描");
        for(Map<String,Object> apiCase:agent?safeApiCases(apiCases):apiCases) {
            String url=java.net.URI.create(target.baseUrl()).resolve(String.valueOf(apiCase.getOrDefault("path","/"))).toString();
            targetScope.requireAllowed(target,url);
            String method=String.valueOf(apiCase.getOrDefault("method","GET")).toUpperCase();
            if(!List.of("GET","HEAD","OPTIONS").contains(method) && !target.allowWriteTests())
                throw ApiException.badRequest("WRITE_TEST_NOT_ALLOWED","套件包含写接口，但目标尚未授权写测试");
        }
        PersistentScanDispatcher.capacity(jdbc,workerOnly);
        String runId=Ulids.next(); LocalDateTime now=LocalDateTime.now();
        Map<String,Object> snapshot=new LinkedHashMap<>();
        snapshot.put("schemaVersion","0.6.1"); snapshot.put("target",target);snapshot.put("targetUpdatedAt",target.updatedAt().toString()); snapshot.put("pinnedAddress",targetScope.pinnedAddress(target));
        snapshot.put("executionOwner",workerOnly?"HOSTING_WORKER":"PLATFORM");
        if(hosting!=null)hosting.snapshot(target.id()).ifPresent(value->snapshot.put("hosting",value));
        if(agent){
            Map<String,Object> effective=new LinkedHashMap<>(version.definition());
            if(!modules.contains("FRONTEND")){effective.put("pages",List.of());effective.put("flowSteps",List.of());}
            else if(!includeFlow)effective.put("flowSteps",List.of());
            effective.put("apiCases",modules.contains("SECURITY")?safeApiCases(apiCases):List.of());
            if(!modules.contains("PERFORMANCE"))effective.put("performance",Map.of());
            snapshot.put("suite",new TestSuiteService.SuiteVersion(version.id(),version.suiteId(),version.projectId(),version.versionNo(),effective,version.createdAt()));
            snapshot.put("agentExcludedApiCaseCount",apiCases.size()-safeApiCases(apiCases).size());
        }else snapshot.put("suite",version);
        snapshot.put("modules",modules); snapshot.put("activeScan",request.activeScan());
        if(agent){snapshot.put("agentSafe",true);snapshot.put("agentIncludeFlow",includeFlow);snapshot.put("agentTaskId",taskId);snapshot.put("agentTargetUpdatedAt",target.updatedAt().toString());}
        Map<String,Object> tools=Map.of(
                "frontend",Map.of("version","browser-quality-0.6.1","engine","Playwright"),
                "security",Map.of("version","transport-security-0.6.1","zapImage",zap.image(),"zapDigest",zap.digest()),
                "performance",Map.of("version","load-performance-0.6.1","k6Image",k6.image(),"k6Digest",k6.digest()));
        jdbc.update("INSERT INTO test_runs(id,project_id,target_id,suite_version_id,status,cancel_requested,config_snapshot,tool_snapshot,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                runId,request.projectId(),request.targetId(),request.suiteVersionId(),"PENDING",false,write(snapshot),write(tools),actorId,now);
        for(String module:modules) jdbc.update("INSERT INTO test_run_modules(id,run_id,module_type,status) VALUES (?,?,?,'PENDING')",Ulids.next(),runId,module);
        idempotency.save(key,idempotencyOperation,runId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){publish(runId);}});
        return hydrate(systemGet(runId));
    }

    @Transactional
    public String createScheduled(String projectId,String targetId,String suiteVersionId,boolean activeScan,boolean includeUpload,boolean includeLoad){
        return createScheduled(projectId,targetId,suiteVersionId,activeScan,includeUpload,includeLoad,null);
    }
    @Transactional
    public String createScheduled(String projectId,String targetId,String suiteVersionId,boolean activeScan,boolean includeUpload,boolean includeLoad,String creator){
        access.requireMutable(projectId);
        if(creator==null){var admins=jdbc.queryForList("SELECT m.user_id FROM project_members m JOIN app_users u ON u.id=m.user_id WHERE m.project_id=? AND m.role='ADMIN' AND u.enabled=TRUE ORDER BY m.user_id LIMIT 1",String.class,projectId);if(admins.isEmpty())throw ApiException.forbidden("回归计划没有有效执行者");creator=admins.getFirst();}
        access.requireActor(projectId,creator);
        OnlineTargetService.TargetView target=targets.systemGet(projectId,targetId);TestSuiteService.SuiteVersion version=suites.systemVersion(suiteVersionId);if(!projectId.equals(version.projectId()))throw ApiException.badRequest("SUITE_PROJECT_MISMATCH","测试套件不属于当前项目");
        List<Map<String,Object>>apiCases=mapList(version.definition().get("apiCases"));if(activeScan&&(apiCases.stream().noneMatch(zap::eligible)||!target.allowActiveScan()))activeScan=false;
        if(hosting!=null&&hosting.snapshot(target.id()).filter(value->"HOST".equals(value.get("environmentKind"))).isPresent()&&(activeScan||includeUpload||includeLoad||!mapList(version.definition().get("flowSteps")).isEmpty()||apiCases.stream().anyMatch(c->!Set.of("GET","HEAD","OPTIONS").contains(String.valueOf(c.getOrDefault("method","GET"))))))throw ApiException.badRequest("HOSTED_TEST_ENVIRONMENT_REQUIRED","正式网站的主动、写入和负载检查需要独立检测环境");
        List<String>modules=includeLoad?MODULE_ORDER:List.of("FRONTEND","SECURITY");PersistentScanDispatcher.capacity(jdbc,workerOnly);String runId=Ulids.next();LocalDateTime now=LocalDateTime.now();Map<String,Object>snapshot=new LinkedHashMap<>();snapshot.put("schemaVersion","0.6.1");snapshot.put("schedule","DAILY_REGRESSION");snapshot.put("executionOwner",workerOnly?"HOSTING_WORKER":"PLATFORM");if(hosting!=null)hosting.snapshot(target.id()).ifPresent(value->snapshot.put("hosting",value));snapshot.put("target",target);snapshot.put("targetUpdatedAt",target.updatedAt().toString());snapshot.put("pinnedAddress",targetScope.pinnedAddress(target));snapshot.put("suite",version);snapshot.put("modules",modules);snapshot.put("activeScan",activeScan);snapshot.put("uploadEnabled",includeUpload);Map<String,Object>tools=Map.of("frontend",Map.of("version","browser-quality-0.6.1"),"security",Map.of("version","transport-security-0.6.1","zapImage",zap.image(),"zapDigest",zap.digest()),"performance",Map.of("version","load-performance-0.6.1","k6Image",k6.image(),"k6Digest",k6.digest()));
        jdbc.update("INSERT INTO test_runs(id,project_id,target_id,suite_version_id,status,cancel_requested,config_snapshot,tool_snapshot,created_at,created_by) VALUES (?,?,?,?, 'PENDING',FALSE,?,?,?,?)",runId,projectId,targetId,suiteVersionId,write(snapshot),write(tools),now,creator);for(String module:modules)jdbc.update("INSERT INTO test_run_modules(id,run_id,module_type,status) VALUES (?,?,?,'PENDING')",Ulids.next(),runId,module);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){publish(runId);}});return runId;
    }

    public RunView get(String id) {
        RunView run=systemGet(id); access.require(run.projectId(),"VIEWER"); return hydrate(run);
    }
    RunView agentGet(String id) { return hydrate(systemGet(id)); }
    void agentCancel(String id) {
        cancellation.add(id);
        jdbc.update("UPDATE test_runs SET cancel_requested=TRUE WHERE id=? AND status IN ('PENDING','RUNNING')",id);
        jdbc.update("UPDATE test_run_modules SET status='CANCELLED',summary='排队时已取消',finished_at=? WHERE run_id=? AND status='PENDING' AND run_id IN (SELECT id FROM test_runs WHERE status='PENDING')",LocalDateTime.now(),id);
        if(jdbc.update("UPDATE test_runs SET status='CANCELLED',finished_at=? WHERE id=? AND status='PENDING'",LocalDateTime.now(),id)>0){cancellation.remove(id);publish(id);}
        zap.cancel(id);k6.cancel(id);
    }
    public List<RunView> list(String projectId) {
        access.require(projectId,"VIEWER");
        return jdbc.query("SELECT * FROM test_runs WHERE project_id=? ORDER BY created_at DESC LIMIT 100",this::mapRun,projectId).stream().map(this::hydrate).toList();
    }
    public List<RecentRun> recent() {
        String userId=access.currentUser().id();
        return jdbc.query("SELECT r.id,r.project_id,p.name AS project_name,r.status,r.created_at,"
                +"(SELECT COUNT(*) FROM run_issues i WHERE i.run_id=r.id) AS issue_count "
                +"FROM test_runs r JOIN projects p ON p.id=r.project_id "
                +"JOIN project_members m ON m.project_id=r.project_id AND m.user_id=? "
                +"ORDER BY r.created_at DESC LIMIT 6",
                (rs,n)->new RecentRun(rs.getString("id"),rs.getString("project_id"),rs.getString("project_name"),
                        rs.getString("status"),rs.getTimestamp("created_at").toLocalDateTime(),rs.getInt("issue_count")),userId);
    }
    public SseEmitter subscribe(String id) {var current=get(id);String user=access.currentUser().id();long authVersion=access.version(user);var emitter=events.subscribe(current,()->{Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM project_members m JOIN app_users u ON u.id=m.user_id WHERE m.project_id=? AND m.user_id=? AND u.enabled=TRUE AND u.auth_version=?",Integer.class,current.projectId(),user,authVersion);return count!=null&&count>0;});events.refreshFromDatabase(id,()->hydrate(systemGet(id)));return emitter;}

    @Transactional
    public RunView cancel(String id) {
        RunView run=get(id); access.require(run.projectId(),"MEMBER");
        if(Set.of("COMPLETED","PARTIAL","FAILED","CANCELLED").contains(run.status())) return run;
        cancellation.add(id); jdbc.update("UPDATE test_runs SET cancel_requested=TRUE WHERE id=?",id);if("PENDING".equals(run.status())){jdbc.update("UPDATE test_run_modules SET status='CANCELLED',summary='排队时已取消',finished_at=? WHERE run_id=? AND status='PENDING'",LocalDateTime.now(),id);jdbc.update("UPDATE test_runs SET status='CANCELLED',finished_at=? WHERE id=? AND status='PENDING'",LocalDateTime.now(),id);publish(id);} zap.cancel(id); k6.cancel(id);
        return get(id);
    }

    void executePending(String id){execute(id);}
    private void execute(String id) {
        RunView initial=systemGet(id);
        if(jdbc.update("UPDATE test_runs SET status='RUNNING',started_at=? WHERE id=? AND status='PENDING' AND cancel_requested=FALSE",LocalDateTime.now(),id)!=1)return; publish(id);
        try {
        Map<String,Object> snapshot=read(initial.configSnapshot());
        @SuppressWarnings("unchecked") Map<String,Object> suite=((Map<String,Object>)snapshot.get("suite"));
        @SuppressWarnings("unchecked") Map<String,Object> definition=suite==null?Map.of():(Map<String,Object>)suite.getOrDefault("definition",Map.of());
        OnlineTargetService.TargetView target=targets.systemGet(initial.projectId(),initial.targetId());
        if(Boolean.TRUE.equals(snapshot.get("agentSafe"))
                && !Objects.equals(snapshot.get("agentTargetUpdatedAt"),target.updatedAt().toString())) {
            for(String module:moduleTypes(id))finishModule(id,module,"FAILED","目标配置在计划确认后发生变化",Map.of("error","AGENT_TARGET_CHANGED"));
            finishRun(id);publish(id);return;
        }
        Object frozenTarget=snapshot.get("targetUpdatedAt");if(frozenTarget!=null&&!Objects.equals(frozenTarget,target.updatedAt().toString()))throw ApiException.conflict("TARGET_CHANGED","目标配置在排队后发生变化，请重新确认检测");
        List<String> modules=moduleTypes(id);
        for(String module:MODULE_ORDER) {
            if(!modules.contains(module)) continue;
            if(cancelled(id)) { finishModule(id,module,"CANCELLED","运行已取消",Map.of()); continue; }
            startModule(id,module);
            try {
                switch(module) {
                    case "FRONTEND" -> runFrontend(id,target,definition,Boolean.TRUE.equals(snapshot.get("agentSafe")),Boolean.TRUE.equals(snapshot.get("agentIncludeFlow")));
                    case "SECURITY" -> runSecurity(id,target,definition,Boolean.TRUE.equals(snapshot.get("activeScan")),
                            !"DAILY_REGRESSION".equals(snapshot.get("schedule"))||Boolean.TRUE.equals(snapshot.get("uploadEnabled")),Boolean.TRUE.equals(snapshot.get("agentSafe")));
                    case "PERFORMANCE" -> runPerformance(id,target,definition);
                    default -> finishModule(id,module,"SKIPPED","未知模块",Map.of());
                }
            } catch(Exception ex) {
                finishModule(id,module,"FAILED",concise(ex),Map.of("error",concise(ex)));
            }
        }
        finishRun(id); cancellation.remove(id); publish(id);
        }catch(Exception ex){for(String module:moduleTypes(id))jdbc.update("UPDATE test_run_modules SET status='FAILED',summary=?,finished_at=? WHERE run_id=? AND module_type=? AND status IN ('RUNNING','PENDING')",concise(ex),LocalDateTime.now(),id,module);finishRun(id);publish(id);}
    }

    private void runFrontend(String id,OnlineTargetService.TargetView target,Map<String,Object> definition,boolean agent,boolean includeFlow) {
        List<Map<String,Object>> flows=agent&&!includeFlow?List.of():mapList(definition.get("flowSteps"),20);
        OnlineFrontendScanner.Output output=frontend.scan(target,stringList(definition.get("pages")),flows,()->cancelled(id),agent,includeFlow);
        saveIssues(id,"FRONTEND",output.issues());
        boolean unavailable=output.issues().stream().anyMatch(issue->"PAGE_UNAVAILABLE".equals(issue.key()))
                ||output.issues().stream().anyMatch(issue->"AGENT_WRITE_BLOCKED".equals(issue.key()))
                ||output.measurements().stream().anyMatch(sample->"UNAVAILABLE".equals(sample.get("status")));
        finishModule(id,"FRONTEND",output.cancelled()?"CANCELLED":unavailable?"PARTIAL":"COMPLETED",
                "检测 "+output.measurements().stream().map(sample->sample.get("pageUrl")).distinct().count()+" 个页面、"+output.checkedLinks()+" 个同站链接，发现 "+output.issues().size()+" 个问题",toMap(output));
    }

    private void runSecurity(String id,OnlineTargetService.TargetView target,Map<String,Object> definition,boolean active,boolean includeUpload,boolean agent) {
        OnlineSecurityScanner.Output base=security.inspect(target);
        List<OnlineFrontendScanner.IssueDraft> issues=new ArrayList<>(base.issues());
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("transport",base.evidence());
        List<Map<String,Object>> cases=(agent?safeApiCases(mapList(definition.get("apiCases"))):mapList(definition.get("apiCases"))).stream()
                .filter(item->includeUpload||!"UPLOAD_SECURITY".equalsIgnoreCase(String.valueOf(item.get("kind")))).toList();
        try {
            ApiSecurityChecker.Output apiOutput=apiSecurity.check(target,cases,credentialId->{var secret=targets.systemSecret(systemGet(id).projectId(),credentialId);return hosting==null?secret:hosting.rebind(secret,target);},()->cancelled(id));
            issues.addAll(apiOutput.issues()); payload.put("apiCases",apiOutput.results());
        } catch(Exception ex){
            payload.put("apiCases",Map.of("status","FAILED","error",concise(ex)));
            saveIssues(id,"SECURITY",issues);
            finishModule(id,"SECURITY","PARTIAL","接口检查异常；已保留传输检查结果："+concise(ex),payload);
            return;
        }
        String status="COMPLETED";
        if(active && !cancelled(id)) {
            try {
                ZapActiveScanRunner.Output result=zap.run(id,target,cases);
                payload.put("zap",Map.of("status",result.status(),"image",result.image(),"exitCode",result.exitCode(),"error",Objects.toString(result.error(),""),"logTail",redact(result.logTail())));
                issues.addAll(zapIssues(result.reportJson()));
                if(!"COMPLETED".equals(result.status())) status="PARTIAL";
            } catch(Exception ex){payload.put("zap",Map.of("status","FAILED","error",concise(ex)));status="PARTIAL";}
        } else payload.put("zap",Map.of("status",active?"CANCELLED":"SKIPPED"));
        if(issues.stream().anyMatch(issue->"INCONCLUSIVE".equals(issue.confidence())))status="PARTIAL";
        saveIssues(id,"SECURITY",issues);
        finishModule(id,"SECURITY",cancelled(id)?"CANCELLED":status,"安全检查发现 "+issues.size()+" 个候选或配置风险",payload);
    }

    @SuppressWarnings("unchecked")
    private void runPerformance(String id,OnlineTargetService.TargetView target,Map<String,Object> definition) {
        Map<String,Object> config=definition.get("performance") instanceof Map<?,?> map?(Map<String,Object>)map:Map.of();
        K6PerformanceRunner.Output result=k6.run(id,target,config);
        String status="COMPLETED".equals(result.status())?"COMPLETED":result.status().equals("CANCELLED")?"CANCELLED":result.status().equals("PARTIAL")?"PARTIAL":"FAILED";
        List<OnlineFrontendScanner.IssueDraft> issues=new ArrayList<>();
        PerformanceAssessment assessment=PerformanceAssessment.evaluate(result.summary(),config,status);
        if(assessment.thresholdsFailed()) issues.add(new OnlineFrontendScanner.IssueDraft("PERFORMANCE_THRESHOLD","K6","MEDIUM","CONFIRMED",
                "性能验收阈值未通过","本次负载配置下 P95 或错误率超过保存的阈值。",Map.of("summary",result.summary())));
        if("PARTIAL".equals(status)) issues.add(new OnlineFrontendScanner.IssueDraft("PERFORMANCE_ABORT","K6","HIGH","CONFIRMED",
                "高错误率触发负载保护","预热后连续两个 10 秒窗口错误率超过 20%，负载已提前停止；未执行阶段不表示通过。",Map.of("summary",result.summary())));
        saveIssues(id,"PERFORMANCE",issues);
        finishModule(id,"PERFORMANCE",status,"COMPLETED".equals(status)?("PASSED".equals(assessment.status())?"负载执行已结束，性能阈值通过":"负载执行已结束，性能阈值未通过"):"k6 未完成："+Objects.toString(result.error(),result.status()),
                Map.of("status",result.status(),"image",result.image(),"exitCode",result.exitCode(),"summary",result.summary(),"logTail",redact(result.logTail()),"error",Objects.toString(result.error(),"")));
    }

    void saveIssues(String runId,String module,List<OnlineFrontendScanner.IssueDraft> drafts) {
      transactions.executeWithoutResult(ignored->saveIssuesInTransaction(runId,module,drafts));
    }
    private void saveIssuesInTransaction(String runId,String module,List<OnlineFrontendScanner.IssueDraft> drafts){
        String moduleId=jdbc.queryForObject("SELECT id FROM test_run_modules WHERE run_id=? AND module_type=?",String.class,runId,module);
        String projectId=systemGet(runId).projectId(); LocalDateTime now=LocalDateTime.now();
        for(OnlineFrontendScanner.IssueDraft draft:drafts) {
            String hostedSite=hosting==null?null:hosting.snapshot(systemGet(runId).targetId()).map(value->(String)value.get("siteId")).orElse(null);
            String issueKey=IssueFingerprint.of(module,draft,hostedSite);
            jdbc.update("INSERT INTO run_issues(id,project_id,run_id,module_id,issue_key,source,severity,evidence_confidence,review_status,task_status,title,description,evidence,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Ulids.next(),projectId,runId,moduleId,issueKey,draft.source(),draft.severity(),draft.confidence(),"PENDING_REVIEW","OPEN",
                    draft.title(),draft.description(),write(draft.evidence()),now,now);
        }
    }

    private void startModule(String id,String module) { jdbc.update("UPDATE test_run_modules SET status='RUNNING',started_at=? WHERE run_id=? AND module_type=?",LocalDateTime.now(),id,module); publish(id); }
    private void finishModule(String id,String module,String status,String summary,Object payload) { jdbc.update("UPDATE test_run_modules SET status=?,summary=?,result_payload=?,finished_at=? WHERE run_id=? AND module_type=?",status,limit(summary,1000),write(payload),LocalDateTime.now(),id,module); publish(id); }
    private void finishRun(String id) {
        List<String> statuses=jdbc.query("SELECT status FROM test_run_modules WHERE run_id=?",(rs,n)->rs.getString(1),id);
        String status=aggregateStatus(statuses,cancelled(id));
        jdbc.update("UPDATE test_runs SET status=?,finished_at=? WHERE id=?",status,LocalDateTime.now(),id);
        cancellation.remove(id);
        if("COMPLETED".equals(status)) detectRecurrences(id);
    }
    static String aggregateStatus(List<String> statuses,boolean cancelled){
        if(cancelled||statuses.contains("CANCELLED"))return "CANCELLED";
        if(!statuses.isEmpty()&&statuses.stream().allMatch(value->Set.of("COMPLETED","SKIPPED").contains(value)))return "COMPLETED";
        if(statuses.stream().anyMatch(value->Set.of("COMPLETED","PARTIAL").contains(value)))return "PARTIAL";
        return "FAILED";
    }
    private void detectRecurrences(String runId){
        RunView current=systemGet(runId);List<String>previous=jdbc.query("SELECT id FROM test_runs WHERE target_id=? AND suite_version_id=? AND status='COMPLETED' AND id<>? AND created_at<? ORDER BY created_at DESC LIMIT 1",(rs,n)->rs.getString(1),current.targetId(),current.suiteVersionId(),runId,current.createdAt());if(previous.isEmpty())return;String previousId=previous.getFirst();
        Map<String,Object>currentConfig=read(current.configSnapshot()),previousConfig=read(systemGet(previousId).configSnapshot());
        if(!sameRecurrenceScope(moduleTypes(runId),moduleTypes(previousId),currentConfig,previousConfig))return;
        List<Map<String,Object>>currentIssues=jdbc.queryForList("SELECT id,issue_key,title FROM run_issues WHERE run_id=? AND evidence_confidence NOT IN ('INCONCLUSIVE','CONFIGURATION')",runId);
        for(Map<String,Object>issue:currentIssues){String key=String.valueOf(issue.get("issue_key"));Integer inPrevious=jdbc.queryForObject("SELECT COUNT(*) FROM run_issues WHERE run_id=? AND issue_key=? AND evidence_confidence NOT IN ('INCONCLUSIVE','CONFIGURATION')",Integer.class,previousId,key);if(inPrevious!=null&&inPrevious>0)continue;List<String>older=jdbc.query("SELECT i.id FROM run_issues i JOIN test_runs r ON r.id=i.run_id WHERE r.target_id=? AND r.suite_version_id=? AND r.created_at<? AND i.issue_key=? AND i.task_status='VERIFIED' ORDER BY r.created_at DESC LIMIT 1",(rs,n)->rs.getString(1),current.targetId(),current.suiteVersionId(),current.createdAt(),key);if(older.isEmpty())continue;String oldIssue=older.getFirst();jdbc.update("UPDATE run_issues SET recurrence_of_issue_id=?,task_status='REOPENED',updated_at=? WHERE id=?",oldIssue,LocalDateTime.now(),String.valueOf(issue.get("id")));List<String>cases=jdbc.query("SELECT id FROM knowledge_cases WHERE issue_id=? AND status='PUBLISHED'",(rs,n)->rs.getString(1),oldIssue);Map<String,Object>payload=new LinkedHashMap<>();payload.put("runId",runId);payload.put("issueId",issue.get("id"));payload.put("previousIssueId",oldIssue);payload.put("knowledgeCaseIds",cases);jdbc.update("INSERT INTO notifications(id,project_id,user_id,type,title,payload,created_at) VALUES (?,?,NULL,'ISSUE_RECURRENCE',?,?,?)",Ulids.next(),current.projectId(),"已修复问题再次出现："+issue.get("title"),write(payload),LocalDateTime.now());}
    }
    static boolean sameRecurrenceScope(List<String> currentModules,List<String> previousModules,
                                       Map<String,Object> currentConfig,Map<String,Object> previousConfig){
        return new HashSet<>(currentModules).equals(new HashSet<>(previousModules))
                &&Boolean.TRUE.equals(currentConfig.get("activeScan"))==Boolean.TRUE.equals(previousConfig.get("activeScan"))
                &&Objects.equals(Boolean.TRUE.equals(currentConfig.get("agentSafe")),Boolean.TRUE.equals(previousConfig.get("agentSafe")))
                &&Objects.equals(Boolean.TRUE.equals(currentConfig.get("agentIncludeFlow")),Boolean.TRUE.equals(previousConfig.get("agentIncludeFlow")))
                &&RunComparisonService.uploadEnabled(currentConfig)==RunComparisonService.uploadEnabled(previousConfig);
    }
    private void publish(String id) { try { events.publish(hydrate(systemGet(id))); } catch(Exception ignored){} }
    private boolean cancelled(String id) { if(cancellation.contains(id))return true;var actor=jdbc.queryForMap("SELECT project_id,created_by FROM test_runs WHERE id=?",id);if(actor.get("created_by")!=null)try{access.requireActor(actor.get("project_id").toString(),actor.get("created_by").toString());}catch(ApiException denied){jdbc.update("UPDATE test_runs SET cancel_requested=TRUE,error_message=? WHERE id=?",denied.getMessage(),id);return true;} Boolean value=jdbc.queryForObject("SELECT cancel_requested FROM test_runs WHERE id=?",Boolean.class,id); return Boolean.TRUE.equals(value); }
    private List<String> moduleTypes(String id){return jdbc.query("SELECT module_type FROM test_run_modules WHERE run_id=?",(rs,n)->rs.getString(1),id);}
    private RunView systemGet(String id){List<RunView>rows=jdbc.query("SELECT * FROM test_runs WHERE id=?",this::mapRun,id);if(rows.isEmpty())throw ApiException.notFound("测试运行不存在");return rows.getFirst();}
    private RunView hydrate(RunView run){
        Map<String,Object> snapshot=read(run.configSnapshot());
        Map<?,?> suite=snapshot.get("suite") instanceof Map<?,?> value?value:Map.of();
        Map<?,?> definition=suite.get("definition") instanceof Map<?,?> value?value:Map.of();
        Map<String,Object> load=definition.get("performance") instanceof Map<?,?> value?read(write(value)):Map.of();
        List<ModuleView> interpreted=modules(run.id()).stream().map(module->{
            if(!"PERFORMANCE".equals(module.moduleType()))return module;
            Map<String,Object> payload=module.resultPayload()==null?Map.of():read(module.resultPayload());
            Map<String,Object> summary=payload.get("summary") instanceof Map<?,?> value?read(write(value)):Map.of();
            return new ModuleView(module.id(),module.moduleType(),module.status(),module.summary(),module.resultPayload(),module.startedAt(),module.finishedAt(),PerformanceAssessment.evaluate(summary,load,module.status()));
        }).toList();
        return new RunView(run.id(),run.projectId(),run.targetId(),run.suiteVersionId(),run.status(),run.cancelRequested(),run.configSnapshot(),run.toolSnapshot(),run.errorMessage(),run.createdAt(),run.startedAt(),run.finishedAt(),interpreted,issues(run.id()));
    }
    private RunView mapRun(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new RunView(rs.getString("id"),rs.getString("project_id"),rs.getString("target_id"),rs.getString("suite_version_id"),rs.getString("status"),rs.getBoolean("cancel_requested"),rs.getString("config_snapshot"),rs.getString("tool_snapshot"),rs.getString("error_message"),rs.getTimestamp("created_at").toLocalDateTime(),rs.getTimestamp("started_at")==null?null:rs.getTimestamp("started_at").toLocalDateTime(),rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toLocalDateTime(),List.of(),List.of());}
    private List<ModuleView> modules(String id){return jdbc.query("SELECT * FROM test_run_modules WHERE run_id=? ORDER BY FIELD(module_type,'FRONTEND','SECURITY','PERFORMANCE')",(rs,n)->new ModuleView(rs.getString("id"),rs.getString("module_type"),rs.getString("status"),rs.getString("summary"),rs.getString("result_payload"),rs.getTimestamp("started_at")==null?null:rs.getTimestamp("started_at").toLocalDateTime(),rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toLocalDateTime()),id);}
    private List<IssueView> issues(String id){return jdbc.query("SELECT id,module_id,source,severity,evidence_confidence,review_status,task_status,title,description,evidence,created_at FROM run_issues WHERE run_id=? ORDER BY FIELD(severity,'HIGH','MEDIUM','LOW'),created_at",(rs,n)->new IssueView(rs.getString("id"),rs.getString("module_id"),rs.getString("source"),rs.getString("severity"),rs.getString("evidence_confidence"),rs.getString("review_status"),rs.getString("task_status"),rs.getString("title"),rs.getString("description"),rs.getString("evidence"),rs.getTimestamp("created_at").toLocalDateTime()),id);}

    private List<String> normalizeModules(List<String> modules){List<String>values=modules==null||modules.isEmpty()?MODULE_ORDER:modules.stream().map(String::toUpperCase).distinct().toList();if(!MODULE_ORDER.containsAll(values))throw ApiException.badRequest("INVALID_MODULE","检测模块无效");return MODULE_ORDER.stream().filter(values::contains).toList();}
    static List<Map<String,Object>> safeApiCases(List<Map<String,Object>> cases) {
        return cases.stream().filter(item->{
            String method=Objects.toString(item.getOrDefault("method","GET")).toUpperCase(Locale.ROOT);
            String kind=Objects.toString(item.getOrDefault("kind","ASSERTION")).toUpperCase(Locale.ROOT);
            return Set.of("GET","HEAD","OPTIONS").contains(method) && !"UPLOAD_SECURITY".equals(kind)
                    && Objects.toString(item.get("body"),"").isBlank();
        }).toList();
    }
    @SuppressWarnings("unchecked") private List<String> stringList(Object value){return value instanceof List<?> list?list.stream().filter(String.class::isInstance).map(String.class::cast).limit(10).toList():List.of();}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> mapList(Object value){return mapList(value,10);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> mapList(Object value,int limit){return value instanceof List<?> list?list.stream().filter(Map.class::isInstance).map(item->(Map<String,Object>)item).limit(limit).toList():List.of();}
    @SuppressWarnings("unchecked") private Map<String,Object> read(String value){try{return (Map<String,Object>)json.readValue(value,Map.class);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private Map<String,Object> toMap(Object value){return read(write(value));}
    @SuppressWarnings("unchecked") private List<OnlineFrontendScanner.IssueDraft> zapIssues(String report){
        if(report==null||report.isBlank())return List.of(); List<OnlineFrontendScanner.IssueDraft> result=new ArrayList<>();
        try{Map<String,Object>root=(Map<String,Object>)json.readValue(report,Map.class);Object sites=root.get("site");if(!(sites instanceof List<?>siteList))return result;
            for(Object site:siteList){if(!(site instanceof Map<?,?>sm)||!(sm.get("alerts") instanceof List<?>alerts))continue;for(Object alert:alerts){if(!(alert instanceof Map<?,?>a))continue;String plugin=Objects.toString(a.get("pluginid"),"");if(!Set.of("40012","40018").contains(plugin))continue;
                List<?> instances=a.get("instances") instanceof List<?> list&&!list.isEmpty()?list:List.of(Map.of());
                for(Object raw:instances){Map<?,?>instance=raw instanceof Map<?,?>m?m:Map.of();Map<String,Object>evidence=new LinkedHashMap<>();evidence.put("pluginId",plugin);evidence.put("confidence",Objects.toString(a.get("confidence"),""));evidence.put("risk",Objects.toString(a.get("riskdesc"),""));evidence.put("url",redact(Objects.toString(instance.get("uri"),"")));evidence.put("method",Objects.toString(instance.get("method"),""));evidence.put("parameter",Objects.toString(instance.get("param"),""));evidence.put("attack",redact(Objects.toString(instance.get("attack"),"")));evidence.put("evidence",redact(Objects.toString(instance.get("evidence"),"")));evidence.put("requestHeader",redact(Objects.toString(instance.get("requestHeader"),"")));evidence.put("responseHeader",redact(Objects.toString(instance.get("responseHeader"),"")));
                    result.add(new OnlineFrontendScanner.IssueDraft("ZAP_"+plugin,"ZAP","HIGH","ENGINE_CANDIDATE",Objects.toString(a.get("alert"),plugin.equals("40012")?"反射型 XSS 候选":"SQL 注入候选"),"自动扫描引擎候选不会被自动确认为漏洞；可按需结合脱敏请求响应记录审阅结论。",evidence));}
            }}
        }catch(Exception ignored){}return result;}
    private String redact(String value){if(value==null)return "";return limit(value.replaceAll("(?i)(authorization|cookie|token|secret|password)\\s*[:=]\\s*[^\\s,;]+","$1=<redacted>"),12000);}
    private String concise(Exception ex){String value=ex.getMessage();return limit(value==null?ex.getClass().getSimpleName():value.lines().findFirst().orElse(value),1000);}
    private String limit(String value,int max){return value==null?"":value.length()<=max?value:value.substring(0,max);}

    public record CreateRun(String projectId,String targetId,String suiteVersionId,List<String> modules,boolean activeScan){}
    public record RecentRun(String id,String projectId,String projectName,String status,LocalDateTime createdAt,int issueCount){}
    public record RunView(String id,String projectId,String targetId,String suiteVersionId,String status,boolean cancelRequested,String configSnapshot,String toolSnapshot,String errorMessage,LocalDateTime createdAt,LocalDateTime startedAt,LocalDateTime finishedAt,List<ModuleView> modules,List<IssueView> issues){}
    public record ModuleView(String id,String moduleType,String status,String summary,String resultPayload,LocalDateTime startedAt,LocalDateTime finishedAt,PerformanceAssessment performanceAssessment){
        public ModuleView(String id,String moduleType,String status,String summary,String resultPayload,LocalDateTime startedAt,LocalDateTime finishedAt){this(id,moduleType,status,summary,resultPayload,startedAt,finishedAt,null);}
    }
    public record IssueView(String id,String moduleId,String source,String severity,String evidenceConfidence,String reviewStatus,String taskStatus,String title,String description,String evidence,LocalDateTime createdAt){}
}
