package com.shijie.accessibility.service;

import com.shijie.accessibility.ai.AgentModelClient;
import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.PerformanceAssessment;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.common.SensitiveText;
import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.security.ProjectAccessService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AgentTaskService {
    @org.springframework.beans.factory.annotation.Value("${app.hosting.worker-only:false}") private boolean workerOnly;
    private static final Logger log=LoggerFactory.getLogger(AgentTaskService.class);
    private static final Set<String> TERMINAL=Set.of("COMPLETED","PARTIAL","FAILED","CANCELLED");
    private static final Duration LIMIT=Duration.ofMinutes(15);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectAccessService access;
    private final OnlineTargetService targets;
    private final TestSuiteService suites;
    private final TestRunService runs;
    private final IdempotencyService idempotency;
    private final AgentModelClient model;
    private final RunComparisonService comparisons;
    private final TaskExecutor executor;
    private final AgentTaskEvents events;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private StreamRevocations revocations;

    public AgentTaskService(JdbcTemplate jdbc,ObjectMapper json,ProjectAccessService access,OnlineTargetService targets,
                            TestSuiteService suites,TestRunService runs,IdempotencyService idempotency,
                            AgentModelClient model,@Qualifier("agentExecutor") TaskExecutor executor,AgentTaskEvents events) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.targets=targets;this.suites=suites;
        this.runs=runs;this.idempotency=idempotency;this.model=model;this.executor=executor;this.events=events;
        this.comparisons=new RunComparisonService(runs,json);
    }

    @PostConstruct
    void recoverInterrupted() {
        if(workerOnly)return;
        List<String> ids=jdbc.query("SELECT id FROM agent_tasks WHERE status='RUNNING' AND started_at IS NOT NULL",(rs,n)->rs.getString(1));
        for(String id:ids){
            Integer completed=jdbc.queryForObject("SELECT COUNT(*) FROM agent_steps WHERE task_id=? AND action='RUN_MODULE' AND run_id IS NOT NULL AND result_json IS NOT NULL",Integer.class,id);
            String reason="服务重启导致智能体任务中断；不会自动重试";
            jdbc.update("UPDATE agent_steps SET status='INTERRUPTED',result_json=?,finished_at=? WHERE task_id=? AND status='RUNNING'",
                    write(Map.of("status","INTERRUPTED","message",reason)),LocalDateTime.now(),id);
            complete(id,completed!=null&&completed>0?"PARTIAL":"FAILED",reason);
        }
    }

    public TaskView createDraft(String projectId,String targetId,String suiteVersionId,String goal,boolean aiConsent) {
        return createDraft(projectId,targetId,suiteVersionId,goal,aiConsent,"QWEN",null);
    }

    public TaskView createDraft(String projectId,String targetId,String suiteVersionId,String goal,boolean aiConsent,String requestedMode,String baselineTaskId) {
        access.require(projectId,"MEMBER");
        String mode=requestedMode==null?"QWEN":requestedMode.strip().toUpperCase(Locale.ROOT);
        if(!Set.of("QWEN","RULE").contains(mode))throw ApiException.badRequest("AGENT_MODE_INVALID","请选择 QWEN 或 RULE 模式");
        if("QWEN".equals(mode)&&!aiConsent) throw ApiException.badRequest("AGENT_AI_CONSENT_REQUIRED","请先确认向模型发送必要的脱敏任务摘要");
        if(goal==null||goal.isBlank()||goal.strip().length()>1000) throw ApiException.badRequest("AGENT_GOAL_INVALID","请填写 1–1000 字的检测目标");
        if("QWEN".equals(mode)&&!model.available()) throw ApiException.badRequest("AGENT_MODEL_UNAVAILABLE",model.unavailableReason());
        OnlineTargetService.TargetView target=targets.get(projectId,targetId);
        TestSuiteService.SuiteVersion version=suites.version(suiteVersionId);
        if(!projectId.equals(version.projectId())) throw ApiException.badRequest("SUITE_PROJECT_MISMATCH","测试套件不属于当前项目");
        TaskView baseline=baselineTaskId==null||baselineTaskId.isBlank()?null:validBaseline(projectId,targetId,suiteVersionId,baselineTaskId);
        Map<String,Object> definition=version.definition();
        List<Map<String,Object>> flows=maps(definition.get("flowSteps"));
        List<Map<String,Object>> cases=maps(definition.get("apiCases"));
        Map<String,Object> input=new LinkedHashMap<>();
        input.put("goal",redact(goal,1000));input.put("targetName",redact(target.name(),120));
        input.put("pageCount",items(definition.get("pages")).size());
        input.put("flowActions",flows.stream().map(item->String.valueOf(item.getOrDefault("action","ASSERT_VISIBLE"))).toList());
        input.put("readOnlyApiCaseCount",TestRunService.safeApiCases(cases).size());
        input.put("performanceConfigured",definition.get("performance") instanceof Map<?,?>);
        AgentModelClient.Plan proposal="QWEN".equals(mode)?model.propose(input)
                :new AgentModelClient.Plan(ruleModules(goal),false,"规则模式按检测目标关键词选择模块；未命中时依次执行三个模块");
        if(proposal==null||proposal.modules()==null||proposal.modules().isEmpty()||proposal.modules().size()>3||new HashSet<>(proposal.modules()).size()!=proposal.modules().size()
                ||!Set.of("FRONTEND","SECURITY","PERFORMANCE").containsAll(proposal.modules()))
            throw ApiException.badRequest("AGENT_PLAN_INVALID","智能体计划包含无效模块");
        boolean includeFlow=proposal.includeFlow()&&proposal.modules().contains("FRONTEND")&&!flows.isEmpty()&&target.allowWriteTests();
        List<Map<String,Object>> flowPreview=includeFlow?flows.stream().map(item->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("action",item.getOrDefault("action","ASSERT_VISIBLE"));
            row.put("selector",item.getOrDefault("selector",""));row.put("url",item.getOrDefault("url",""));
            if(item.containsKey("value"))row.put("value","[已配置，内容不展示]");return row;
        }).toList():List.of();
        List<Map<String,Object>> apiPreview=(proposal.modules().contains("SECURITY")?TestRunService.safeApiCases(cases):List.<Map<String,Object>>of()).stream().map(item->
                Map.<String,Object>of("name",redact(item.get("name"),120),"method",brief(item.getOrDefault("method","GET"),10),
                        "path",String.valueOf(item.getOrDefault("path","/")),"kind",brief(item.getOrDefault("kind","ASSERTION"),40))).toList();
        Map<String,Object> plan=new LinkedHashMap<>();
        plan.put("schemaVersion","0.7.0");plan.put("mode",mode);plan.put("baselineTaskId",baseline==null?"":baseline.id());
        plan.put("baseline",baseline==null?Map.of():baselinePreview(baseline));
        plan.put("modules",proposal.modules());plan.put("includeFlow",includeFlow);plan.put("flowSteps",flowPreview);
        plan.put("pages",items(definition.get("pages")));plan.put("modelInput","QWEN".equals(mode)?input:Map.of());
        plan.put("performance",proposal.modules().contains("PERFORMANCE")?safePerformance(definition.get("performance")):Map.of());
        plan.put("readOnlyApiCases",apiPreview);plan.put("excludedApiCaseCount",proposal.modules().contains("SECURITY")?cases.size()-apiPreview.size():0);
        plan.put("activeScan",false);plan.put("targetName",redact(target.name(),120));plan.put("targetUrl",target.baseUrl());
        plan.put("targetUpdatedAt",target.updatedAt().toString());plan.put("suiteVersion",version.versionNo());
        plan.put("reason",redact(proposal.reason(),500));plan.put("model","QWEN".equals(mode)?model.model():"规则（离线）");
        plan.put("flowUnavailableReason",proposal.includeFlow()&&!includeFlow?"目标未授权交互步骤或套件没有可执行步骤":"");
        String encoded=write(plan),id=Ulids.next();LocalDateTime now=LocalDateTime.now();
        jdbc.update("INSERT INTO agent_tasks(id,project_id,target_id,suite_version_id,created_by,goal,mode,baseline_task_id,status,ai_consent,plan_json,plan_hash,created_at) VALUES (?,?,?,?,?,?,?,?,'AWAITING_START',?,?,?,?)",
                id,projectId,targetId,suiteVersionId,access.currentUser().id(),redact(goal,1000),mode,baseline==null?null:baseline.id(),aiConsent,encoded,sha256(encoded),now);
        return load(id);
    }

    public List<Map<String,Object>> baselines(String projectId,String targetId,String suiteVersionId) {
        access.require(projectId,"VIEWER");
        targets.get(projectId,targetId);
        TestSuiteService.SuiteVersion version=suites.version(suiteVersionId);
        if(!projectId.equals(version.projectId()))throw ApiException.notFound("基线任务不存在");
        return jdbc.query("SELECT id FROM agent_tasks WHERE project_id=? AND target_id=? AND suite_version_id=? AND status IN ('COMPLETED','PARTIAL','FAILED','CANCELLED') ORDER BY finished_at DESC LIMIT 30",
                (rs,n)->rs.getString(1),projectId,targetId,suiteVersionId).stream().map(this::load)
                .filter(task->!fullyCompletedModules(task.steps()).isEmpty()).map(this::baselinePreview).toList();
    }

    private TaskView validBaseline(String projectId,String targetId,String suiteVersionId,String baselineTaskId) {
        TaskView task=load(baselineTaskId);
        if(!projectId.equals(task.projectId())||!targetId.equals(task.targetId())||!suiteVersionId.equals(task.suiteVersionId())
                ||!TERMINAL.contains(task.status())||fullyCompletedModules(task.steps()).isEmpty())
            throw ApiException.notFound("基线任务不存在或没有完整模块");
        return task;
    }

    private Map<String,Object> baselinePreview(TaskView task) {
        Map<String,Object> modules=new LinkedHashMap<>();
        for(String module:List.of("FRONTEND","SECURITY","PERFORMANCE")) {
            StepView step=task.steps().stream().filter(s->"RUN_MODULE".equals(s.action())&&module.equals(s.argument().get("module"))).findFirst().orElse(null);
            modules.put(module,step==null?Map.of("status","MISSING","reason","基线任务未执行该模块")
                    :Map.of("status",step.status(),"runId",Objects.toString(step.runId(),""),"reason",
                        "COMPLETED".equals(step.status())?"":"基线模块未完整执行"));
        }
        return Map.of("id",task.id(),"goal",redact(task.goal(),160),"status",task.status(),
                "finishedAt",Objects.toString(task.finishedAt(),""),"modules",modules);
    }

    static List<String> ruleModules(String goal) {
        String normalized=goal==null?"":goal.toLowerCase(Locale.ROOT);
        List<String> modules=new ArrayList<>();
        if(containsAny(normalized,"页面","前端","导航","可用性","加载","lcp","cls","ttfb","frontend"))modules.add("FRONTEND");
        if(containsAny(normalized,"安全","权限","接口","漏洞","认证","鉴权","security"))modules.add("SECURITY");
        if(containsAny(normalized,"性能","负载","吞吐","响应","延迟","p95","performance"))modules.add("PERFORMANCE");
        return modules.isEmpty()?List.of("FRONTEND","SECURITY","PERFORMANCE"):List.copyOf(modules);
    }
    private static boolean containsAny(String text,String... words){for(String word:words)if(text.contains(word))return true;return false;}

    @Transactional
    public TaskView start(String id,String acceptedPlanHash,String key) {
        TaskView task=get(id);access.require(task.projectId(),"MEMBER");requireCreator(task);
        Optional<String> previous=idempotency.find(key,"start-agent-task");
        if(previous.isPresent()){
            if(!id.equals(previous.get()))throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED","该请求标识已用于其他智能体任务");
            return get(previous.get());
        }
        if(!"AWAITING_START".equals(task.status())) throw ApiException.conflict("AGENT_ALREADY_STARTED","智能体任务已启动或结束");
        if(!task.planHash().equals(acceptedPlanHash)) throw ApiException.conflict("AGENT_PLAN_CHANGED","计划校验不一致，请重新查看计划");
        if(Duration.between(task.createdAt(),LocalDateTime.now()).toMinutes()>=30)
            throw ApiException.conflict("AGENT_PLAN_EXPIRED","计划已超过 30 分钟，请重新生成");
        // The task is bound to an immutable suite version and a registered target.
        OnlineTargetService.TargetView target=targets.get(task.projectId(),task.targetId());
        if(!Objects.equals(target.updatedAt().toString(),task.plan().get("targetUpdatedAt")))
            throw ApiException.conflict("AGENT_TARGET_CHANGED","目标配置在计划确认后发生变化，请重新生成计划");
        suites.version(task.suiteVersionId());
        jdbc.queryForList("SELECT id FROM runtime_queue_locks WHERE id='AGENT' FOR UPDATE");
        Integer queued=jdbc.queryForObject("SELECT COUNT(*) FROM agent_tasks WHERE status='RUNNING'",Integer.class);if(queued!=null&&queued>=20)throw ApiException.conflict("QUEUE_FULL","智能任务队列已满，请稍后再试");
        int started=jdbc.update("UPDATE agent_tasks SET status='RUNNING',started_at=NULL WHERE id=? AND status='AWAITING_START'",id);
        if(started!=1)throw ApiException.conflict("AGENT_ALREADY_STARTED","智能体任务已由另一请求启动");
        idempotency.save(key,"start-agent-task",id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){publish(id);}
        });
        return load(id);
    }

    public TaskView get(String id) {
        TaskView task=load(id);access.require(task.projectId(),"VIEWER");return task;
    }
    public SseEmitter subscribe(String id) {var task=get(id);var emitter=events.subscribe(task);if(revocations!=null){String user=access.currentUser().id();long version=access.version(user);revocations.watch(emitter,()->access.canRead(task.projectId(),user,version));}return emitter;}
    public List<TaskView> list(String projectId) {
        access.require(projectId,"VIEWER");
        return jdbc.query("SELECT id FROM agent_tasks WHERE project_id=? ORDER BY created_at DESC LIMIT 30",(rs,n)->rs.getString(1),projectId)
                .stream().map(this::load).toList();
    }

    @Transactional
    public TaskView cancel(String id) {
        TaskView task=get(id);access.require(task.projectId(),"MEMBER");
        if(TERMINAL.contains(task.status()))return task;
        if("AWAITING_START".equals(task.status())||("RUNNING".equals(task.status())&&task.startedAt()==null)){
            Map<String,Object> summary=new LinkedHashMap<>();summary.put("schemaVersion","0.7.0");summary.put("mode",mode(task));
            summary.put("message","确认前已取消；没有启动检测");summary.put("attemptedModules",List.of());
            summary.put("completedModules",List.of());summary.put("incompleteModules",moduleList(task.plan()));
            summary.put("unexecutedModules",moduleList(task.plan()));summary.put("reviewCandidateCount",0);
            summary.put("runIds",List.of());summary.put("reviewRequired",false);summary.put("moduleEvidence",Map.of());
            summary.put("baselineComparisons",Map.of());
            jdbc.update("UPDATE agent_tasks SET status='CANCELLED',cancel_requested=TRUE,summary_json=?,finished_at=? WHERE id=?",
                    write(summary),LocalDateTime.now(),id);
        }
        else {
            jdbc.update("UPDATE agent_tasks SET cancel_requested=TRUE WHERE id=?",id);
            if(task.currentRunId()!=null)runs.agentCancel(task.currentRunId());
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){publish(id);}
        });
        return load(id);
    }

    private final java.util.concurrent.atomic.AtomicBoolean dispatching=new java.util.concurrent.atomic.AtomicBoolean();
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000,initialDelay=3000) void dispatchPending(){if(workerOnly||!dispatching.compareAndSet(false,true))return;try{var pending=jdbc.queryForList("SELECT id FROM agent_tasks WHERE status='RUNNING' AND started_at IS NULL ORDER BY created_at LIMIT 1");if(pending.isEmpty()){dispatching.set(false);return;}String id=pending.getFirst().get("id").toString();executor.execute(()->{try{execute(id);}finally{dispatching.set(false);}});}catch(Exception ex){dispatching.set(false);}}
    private void execute(String id) {
        if(jdbc.update("UPDATE agent_tasks SET started_at=? WHERE id=? AND status='RUNNING' AND started_at IS NULL AND cancel_requested=FALSE",LocalDateTime.now(),id)!=1)return;
        try {
            for(int decisionNo=1;decisionNo<=8;decisionNo++) {
                TaskView task=load(id);
                if(!"RUNNING".equals(task.status()))return;
                if(task.cancelRequested()){complete(id,"CANCELLED","用户取消了智能体任务");return;}
                if(expired(task)){if(task.currentRunId()!=null)runs.agentCancel(task.currentRunId());complete(id,"PARTIAL","智能体任务超过 15 分钟");return;}
                requireExecutionPermission(task);
                List<String> done=attemptedModules(task.steps());
                if(done.containsAll(moduleList(task.plan()))) {
                    boolean fullyDone=fullyCompletedModules(task.steps()).containsAll(moduleList(task.plan()));
                    complete(id,null,fullyDone?"已完成计划中的检测":"计划中的检测已尝试，部分模块未完整完成");return;
                }
                List<String> remainingModules=moduleList(task.plan()).stream().filter(m->!done.contains(m)).toList();
                boolean rule="RULE".equals(task.plan().get("mode"));
                AgentModelClient.Decision decision;
                if(rule) decision=new AgentModelClient.Decision("RUN_MODULE",remainingModules.getFirst(),"",
                        "规则模式按确认计划的顺序执行，未调用模型",List.of());
                else {
                    Map<String,Object> context=new LinkedHashMap<>();context.put("goal",redact(task.goal(),1000));
                    context.put("approvedModules",moduleList(task.plan()));context.put("attemptedModules",done);
                    context.put("completedModules",fullyCompletedModules(task.steps()));
                    context.put("remainingModules",remainingModules);
                    List<Map<String,Object>> observations=task.steps().stream().filter(s->s.result()!=null).map(s->Map.<String,Object>of(
                            "stepNo",s.stepNo(),"action",s.action(),"runId",Objects.toString(s.runId(),""),"result",s.result())).toList();
                    context.put("recentObservations",SensitiveText.redactValues(observations.stream().skip(Math.max(0,observations.size()-3)).toList()));
                    decision=model.decide(context);
                }
                // Cancellation and membership can change while a model request is in flight.
                task=load(id);
                if(!"RUNNING".equals(task.status()))return;
                if(task.cancelRequested()){complete(id,"CANCELLED","用户取消了智能体任务");return;}
                if(expired(task)){complete(id,"PARTIAL","智能体任务超过 15 分钟");return;}
                requireExecutionPermission(task);
                if(decision==null||decision.action()==null||!Set.of("RUN_MODULE","READ_RUN","SEARCH_CASES","FINISH").contains(decision.action()))
                    throw new IllegalArgumentException("模型返回了未授权动作");
                if(!rule)validateEvidenceReferences(task.steps(),decision.evidenceStepNos());
                if("SEARCH_CASES".equals(decision.action())&&(decision.query()==null||decision.query().isBlank()||decision.query().length()>80))
                    throw new IllegalArgumentException("模型返回了无效案例查询参数");
                int stepNo=task.steps().size()+1;
                Map<String,Object> decisionEvidence=Map.of("decisionSource",rule?"RULE":"QWEN",
                        "evidenceStepNos",decision.evidenceStepNos()==null?List.of():decision.evidenceStepNos());
                if("FINISH".equals(decision.action())) {
                    step(id,stepNo,"FINISH",decision.reason(),decisionEvidence,Map.of("message","智能体结束任务"),"COMPLETED",null);
                    List<String> remaining=moduleList(task.plan()).stream().filter(m->!done.contains(m)).toList();
                    complete(id,null,remaining.isEmpty()?decision.reason():"智能体根据现有结果提前结束；未执行模块："+String.join("、",remaining));return;
                }
                if("RUN_MODULE".equals(decision.action())) {
                    String module=decision.module();
                    if(!moduleList(task.plan()).contains(module)||done.contains(module)) throw new IllegalArgumentException("模型选择了未批准或已执行的检测模块");
                    Map<String,Object> argument=new LinkedHashMap<>(decisionEvidence);argument.put("module",module);
                    String stepId=beginStep(id,stepNo,"RUN_MODULE",decision.reason(),argument);
                    boolean flow="FRONTEND".equals(module)&&Boolean.TRUE.equals(task.plan().get("includeFlow"));
                    TestRunService.RunView run=runs.createAgent(id,task.projectId(),task.targetId(),task.suiteVersionId(),module,flow,task.createdBy(),
                            String.valueOf(task.plan().get("targetUpdatedAt")));
                    jdbc.update("UPDATE agent_steps SET run_id=? WHERE id=?",run.id(),stepId);
                    jdbc.update("UPDATE agent_tasks SET current_run_id=? WHERE id=?",run.id(),id);
                    publish(id);
                    try {
                        TestRunService.RunView finished=awaitRun(id,run.id());
                        finishStep(stepId,summarizeRunForTask(task,finished),finished.status());
                    } catch(Exception ex) {
                        runs.agentCancel(run.id());
                        finishStep(stepId,Map.of("runId",run.id(),"status","PARTIAL","error",redact(ex.getMessage(),300)),"PARTIAL");
                        throw ex;
                    }
                    jdbc.update("UPDATE agent_tasks SET current_run_id=NULL WHERE id=?",id);
                    publish(id);
                    continue;
                }
                if("READ_RUN".equals(decision.action())) {
                    String lastRun=task.steps().stream().filter(s->s.runId()!=null).map(StepView::runId).reduce((a,b)->b).orElse(null);
                    if(lastRun==null)throw new IllegalArgumentException("没有可读取的检测运行");
                    Map<String,Object> argument=new LinkedHashMap<>(decisionEvidence);argument.put("runId",lastRun);
                    step(id,stepNo,"READ_RUN",decision.reason(),argument,summarizeRunForTask(task,runs.agentGet(lastRun)),"COMPLETED",lastRun);
                    continue;
                }
                if("SEARCH_CASES".equals(decision.action())) {
                    List<Map<String,Object>> cases=jdbc.query("SELECT id,title,category FROM knowledge_cases WHERE project_id=? AND status='PUBLISHED' AND title LIKE ? ORDER BY updated_at DESC LIMIT 3",
                            (rs,n)->Map.<String,Object>of("id",rs.getString("id"),"title",redact(rs.getString("title"),160),
                                    "category",redact(rs.getString("category"),80)),task.projectId(),"%"+redact(decision.query(),80).replace("%","\\%").replace("_","\\_")+"%");
                    Map<String,Object> argument=new LinkedHashMap<>(decisionEvidence);argument.put("query",redact(decision.query(),80));
                    step(id,stepNo,"SEARCH_CASES",decision.reason(),argument,Map.of("cases",cases),"COMPLETED",null);
                }
            }
            TaskView finalTask=load(id);
            boolean allDone=fullyCompletedModules(finalTask.steps()).containsAll(moduleList(finalTask.plan()));
            complete(id,null,allDone?"已完成计划中的检测":"智能体达到 8 次决策上限，部分模块未完整完成");
        }catch(Exception ex){
            log.warn("Agent task {} interrupted by an execution error: {}",id,ex.getClass().getSimpleName());
            TaskView task=load(id);
            if("RUNNING".equals(task.status())){
                if(task.currentRunId()!=null)runs.agentCancel(task.currentRunId());
                if(task.cancelRequested()){
                    jdbc.update("UPDATE agent_steps SET status='CANCELLED',result_json=?,finished_at=? WHERE task_id=? AND status='RUNNING'",
                            write(Map.of("status","CANCELLED","message","用户取消了智能体任务")),LocalDateTime.now(),id);
                    complete(id,"CANCELLED","用户取消了智能体任务");return;
                }
                String message=redact(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage(),500);
                jdbc.update("UPDATE agent_steps SET status='FAILED',result_json=?,finished_at=? WHERE task_id=? AND status='RUNNING'",
                        write(Map.of("error",message)),LocalDateTime.now(),id);
                step(id,task.steps().size()+1,"REJECTED_ACTION",message,Map.of("decisionSource",mode(task)),Map.of("error",message),"FAILED",null);
                complete(id,null,"智能体执行中断："+message);
            }
        }
    }

    private String mode(TaskView task){return "RULE".equals(task.plan().get("mode"))?"RULE":"QWEN";}
    private void validateEvidenceReferences(List<StepView> steps,List<Integer> references) {
        Set<Integer> observed=new HashSet<>();
        for(StepView step:steps)if(step.result()!=null)observed.add(step.stepNo());
        if(references==null||(!observed.isEmpty()&&references.isEmpty())||!observed.containsAll(references)
                ||(observed.isEmpty()&&!references.isEmpty()))
            throw new IllegalArgumentException("模型引用了不存在的观察步骤或未引用已有证据");
    }

    private TestRunService.RunView awaitRun(String taskId,String runId) throws InterruptedException {
        while(true){
            TaskView task=load(taskId);
            if(task.cancelRequested())runs.agentCancel(runId);
            if(expired(task)){runs.agentCancel(runId);throw new IllegalStateException("智能体任务超过 15 分钟");}
            TestRunService.RunView run=runs.agentGet(runId);
            if(TERMINAL.contains(run.status()))return run;
            Thread.sleep(1000);
        }
    }

    private void complete(String id,String desired,String reason) {
        TaskView task=load(id);if(!"RUNNING".equals(task.status()))return;
        List<String> approved=moduleList(task.plan()),attempted=attemptedModules(task.steps());
        List<String> completed=fullyCompletedModules(task.steps());
        List<String> remaining=approved.stream().filter(m->!attempted.contains(m)).toList();
        List<String> incomplete=approved.stream().filter(m->!completed.contains(m)).toList();
        List<StepView> runsDone=task.steps().stream().filter(s->"RUN_MODULE".equals(s.action())&&s.runId()!=null&&s.result()!=null).toList();
        boolean allComplete=runsDone.stream().allMatch(s->"COMPLETED".equals(s.result().get("status")));
        String status=desired!=null?desired:runsDone.isEmpty()?"FAILED":remaining.isEmpty()&&allComplete?"COMPLETED":"PARTIAL";
        int candidates=runsDone.stream().mapToInt(s->((Number)s.result().getOrDefault("reviewCandidateCount",0)).intValue()).sum();
        Map<String,Object> summary=new LinkedHashMap<>();summary.put("schemaVersion","0.7.0");summary.put("mode",mode(task));
        summary.put("decisionSource","RULE".equals(mode(task))?"规则步骤（无模型请求）":"Qwen 模型决策（服务端校验证据引用）");
        summary.put("message",redact(reason,500));summary.put("attemptedModules",attempted);
        summary.put("completedModules",completed);summary.put("incompleteModules",incomplete);
        summary.put("unexecutedModules",remaining);summary.put("reviewCandidateCount",candidates);
        summary.put("runIds",runsDone.stream().map(StepView::runId).toList());summary.put("reviewRequired",true);
        Map<String,Object> evidence=new LinkedHashMap<>();
        Map<String,Object> differences=new LinkedHashMap<>();
        TaskView baseline=baselineFromPlan(task);
        for(StepView step:runsDone){
            String module=String.valueOf(step.argument().get("module"));
            Object moduleEvidence=step.result().get("evidence");
            Object selected=moduleEvidence instanceof Map<?,?> byModule?byModule.get(module):null;
            evidence.put(module,selected==null?Map.of():selected);
            if(baseline!=null){
                StepView previous=baseline.steps().stream().filter(s->"RUN_MODULE".equals(s.action())&&module.equals(s.argument().get("module"))).findFirst().orElse(null);
                if(previous==null)differences.put(module,Map.of("comparable",false,"reasons",List.of("基线任务未执行该模块")));
                else if(!"COMPLETED".equals(previous.status())||previous.runId()==null)
                    differences.put(module,Map.of("comparable",false,"reasons",List.of("基线模块未完整执行")));
                else {
                    try {
                        Map<?,?> compared=(Map<?,?>)comparisons.compareAgent(step.runId(),previous.runId()).get("modules");
                        Object comparison=compared.get(module);
                        differences.put(module,comparison==null?Map.of("comparable",false,"reasons",List.of("比较结果缺失")):comparison);
                    }catch(Exception ex){differences.put(module,Map.of("comparable",false,"reasons",List.of("基线运行不可读取或比较失败")));}
                }
            }
        }
        if(baseline!=null){
            summary.put("baselineTaskId",baseline.id());
            for(String module:approved)if(!differences.containsKey(module))differences.put(module,Map.of("comparable",false,"reasons",List.of("本次任务未执行该模块")));
        }
        summary.put("moduleEvidence",evidence);summary.put("baselineComparisons",differences);
        summary.put("linkedCases",task.steps().stream().filter(s->"SEARCH_CASES".equals(s.action())&&s.result()!=null)
                .flatMap(s->items(s.result().get("cases")).stream()).toList());
        jdbc.update("UPDATE agent_tasks SET status=?,summary_json=?,error_message=?,finished_at=?,current_run_id=NULL WHERE id=? AND status='RUNNING'",
                status,write(summary),Set.of("FAILED","PARTIAL").contains(status)?redact(reason,500):null,LocalDateTime.now(),id);
        publish(id);
    }

    private Map<String,Object> summarizeRun(TestRunService.RunView run) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("runId",run.id());result.put("status",run.status());
        result.put("modules",run.modules().stream().map(module->Map.of("type",module.moduleType(),"status",module.status(),
                "summary",redact(module.summary(),200))).toList());
        Map<String,Object> evidence=new LinkedHashMap<>();
        for(TestRunService.ModuleView module:run.modules()){
            Map<String,Object> item=new LinkedHashMap<>();item.put("status",module.status());
            item.put("metrics",SensitiveText.redactValues(comparisons.agentMetrics(run,module.moduleType())));
            item.put("reason",redact(module.summary(),300));
            if("PERFORMANCE".equals(module.moduleType())){
                Map<String,Object> payload=read(module.resultPayload());
                Map<String,Object> metricSummary=payload.get("summary") instanceof Map<?,?> value?copyMap(value):Map.of();
                Map<String,Object> config=performanceConfig(run);
                PerformanceAssessment assessment=PerformanceAssessment.evaluate(metricSummary,config,module.status());
                item.put("threshold",Map.of("status",assessment.status(),"reason",assessment.reason(),
                        "p95LimitMs",assessment.p95LimitMs(),"errorRateLimit",assessment.errorRateLimit()));
            }
            evidence.put(module.moduleType(),item);
        }
        result.put("evidence",evidence);
        result.put("reviewCandidateCount",run.issues().stream().filter(issue->"PENDING_REVIEW".equals(issue.reviewStatus())).count());
        result.put("issues",run.issues().stream().limit(8).map(issue->Map.of("title",redact(issue.title(),160),
                "severity",issue.severity(),"confidence",issue.evidenceConfidence(),"reviewStatus",issue.reviewStatus())).toList());
        return result;
    }

    private Map<String,Object> summarizeRunForTask(TaskView task,TestRunService.RunView run){
        Map<String,Object> result=summarizeRun(run);
        TaskView baseline=baselineFromPlan(task);
        if(baseline==null)return result;
        String module=run.modules().isEmpty()?"":run.modules().getFirst().moduleType();
        StepView previous=baseline.steps().stream().filter(s->"RUN_MODULE".equals(s.action())&&module.equals(s.argument().get("module"))).findFirst().orElse(null);
        if(previous==null)result.put("baselineComparison",Map.of("comparable",false,"reasons",List.of("基线任务未执行该模块")));
        else if(!"COMPLETED".equals(previous.status())||previous.runId()==null)
            result.put("baselineComparison",Map.of("comparable",false,"reasons",List.of("基线模块未完整执行")));
        else try{
            Map<?,?> compared=(Map<?,?>)comparisons.compareAgent(run.id(),previous.runId()).get("modules");
            result.put("baselineComparison",compared.get(module));
        }catch(Exception ex){result.put("baselineComparison",Map.of("comparable",false,"reasons",List.of("基线运行不可读取或比较失败")));}
        return result;
    }

    private TaskView baselineFromPlan(TaskView task){
        String id=Objects.toString(task.plan().get("baselineTaskId"),"");
        if(id.isBlank())return null;
        try{return validBaseline(task.projectId(),task.targetId(),task.suiteVersionId(),id);}
        catch(Exception ex){return null;}
    }
    private Map<String,Object> performanceConfig(TestRunService.RunView run){
        Map<String,Object> snapshot=read(run.configSnapshot());
        if(snapshot.get("suite") instanceof Map<?,?> suite&&suite.get("definition") instanceof Map<?,?> definition
                &&definition.get("performance") instanceof Map<?,?> performance)return copyMap(performance);
        return Map.of();
    }
    private Map<String,Object> copyMap(Map<?,?> value){
        Map<String,Object> copy=new LinkedHashMap<>();value.forEach((key,item)->copy.put(String.valueOf(key),item));return copy;
    }

    private String beginStep(String taskId,int number,String action,String reason,Object args) {
        String id=Ulids.next();jdbc.update("INSERT INTO agent_steps(id,task_id,step_no,action,reason,argument_json,status,created_at) VALUES (?,?,?,?,?,?,'RUNNING',?)",
                id,taskId,number,action,redact(reason,500),write(args),LocalDateTime.now());publish(taskId);return id;
    }
    private void finishStep(String id,Object result,String status){
        jdbc.update("UPDATE agent_steps SET result_json=?,status=?,finished_at=? WHERE id=?",write(result),status,LocalDateTime.now(),id);
        List<String> taskIds=jdbc.query("SELECT task_id FROM agent_steps WHERE id=?",(rs,n)->rs.getString(1),id);
        if(!taskIds.isEmpty())publish(taskIds.getFirst());
    }
    private void step(String taskId,int number,String action,String reason,Object args,Object result,String status,String runId){
        String id=beginStep(taskId,number,action,reason,args);
        if(runId!=null)jdbc.update("UPDATE agent_steps SET run_id=? WHERE id=?",runId,id);
        finishStep(id,result,status);
    }
    private boolean expired(TaskView task){return task.startedAt()!=null&&Duration.between(task.startedAt(),LocalDateTime.now()).compareTo(LIMIT)>0;}
    private List<String> attemptedModules(List<StepView> steps){return steps.stream().filter(s->"RUN_MODULE".equals(s.action())&&s.runId()!=null&&s.result()!=null)
            .map(s->String.valueOf(s.argument().get("module"))).toList();}
    private List<String> fullyCompletedModules(List<StepView> steps){return steps.stream().filter(s->"RUN_MODULE".equals(s.action())&&s.runId()!=null&&s.result()!=null
                    &&"COMPLETED".equals(s.result().get("status")))
            .map(s->String.valueOf(s.argument().get("module"))).toList();}
    private void requireCreator(TaskView task){if(!Objects.equals(task.createdBy(),access.currentUser().id()))throw ApiException.forbidden("只有计划创建者可以启动该任务");}
    private void requireExecutionPermission(TaskView task){
        Integer allowed=jdbc.queryForObject("SELECT COUNT(*) FROM project_members m JOIN app_users u ON u.id=m.user_id WHERE m.project_id=? AND m.user_id=? AND m.role IN ('MEMBER','ADMIN') AND u.enabled=TRUE",
                Integer.class,task.projectId(),task.createdBy());
        if(allowed==null||allowed==0)throw ApiException.forbidden("任务创建者已无执行权限或账号已停用");
    }
    @SuppressWarnings("unchecked") private List<String> moduleList(Map<String,Object> plan){return (List<String>)plan.getOrDefault("modules",List.of());}
    private Map<String,Object> safePerformance(Object value){
        Map<?,?> raw=value instanceof Map<?,?> map?map:Map.of();
        Map<String,Object> safe=new LinkedHashMap<>(Map.of("vus",List.of(1,5,10),"stageDurationSeconds",20,
                "p95Ms",1000,"errorRate",0.01,"ratePerSecond",50,"path",""));
        for(String key:List.of("vus","stageDurationSeconds","p95Ms","errorRate","ratePerSecond"))if(raw.containsKey(key))safe.put(key,raw.get(key));
        if(raw.containsKey("path"))safe.put("path",raw.get("path"));
        return safe;
    }
    private List<?> items(Object value){return value instanceof List<?> list?list:List.of();}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> maps(Object value){return value instanceof List<?> list?list.stream().filter(Map.class::isInstance).map(v->(Map<String,Object>)v).toList():List.of();}
    private String brief(Object value,int max){String text=value==null?"":String.valueOf(value).strip();return text.length()<=max?text:text.substring(0,max);}
    private String redact(Object value,int max){return SensitiveText.redact(value,max);}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    @SuppressWarnings("unchecked") private Map<String,Object> read(String value){if(value==null)return Map.of();try{return (Map<String,Object>)json.readValue(value,Map.class);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String sha256(String value){try{byte[]digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(digest);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private void publish(String id){try{events.publish(load(id));}catch(Exception ignored){}}

    private TaskView load(String id){
        List<TaskView> rows=jdbc.query("SELECT * FROM agent_tasks WHERE id=?",(rs,n)->new TaskView(rs.getString("id"),rs.getString("project_id"),rs.getString("target_id"),
                rs.getString("suite_version_id"),rs.getString("created_by"),rs.getString("goal"),rs.getString("status"),
                read(rs.getString("plan_json")),rs.getString("plan_hash"),read(rs.getString("summary_json")),
                rs.getString("current_run_id"),rs.getBoolean("cancel_requested"),rs.getString("error_message"),
                rs.getTimestamp("created_at").toLocalDateTime(),rs.getTimestamp("started_at")==null?null:rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toLocalDateTime(),List.of()),id);
        if(rows.isEmpty())throw ApiException.notFound("智能体任务不存在");
        TaskView task=rows.getFirst();
        List<StepView> steps=jdbc.query("SELECT * FROM agent_steps WHERE task_id=? ORDER BY step_no",(rs,n)->new StepView(rs.getInt("step_no"),rs.getString("action"),
                rs.getString("reason"),read(rs.getString("argument_json")),rs.getString("result_json")==null?null:read(rs.getString("result_json")),
                rs.getString("status"),rs.getString("run_id"),rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toLocalDateTime()),id);
        return new TaskView(task.id(),task.projectId(),task.targetId(),task.suiteVersionId(),task.createdBy(),task.goal(),task.status(),
                task.plan(),task.planHash(),task.summary(),task.currentRunId(),task.cancelRequested(),task.errorMessage(),
                task.createdAt(),task.startedAt(),task.finishedAt(),steps);
    }

    public record TaskView(String id,String projectId,String targetId,String suiteVersionId,String createdBy,String goal,String status,
                           Map<String,Object> plan,String planHash,Map<String,Object> summary,String currentRunId,boolean cancelRequested,
                           String errorMessage,LocalDateTime createdAt,LocalDateTime startedAt,LocalDateTime finishedAt,List<StepView> steps){}
    public record StepView(int stepNo,String action,String reason,Map<String,Object> argument,Map<String,Object> result,String status,
                           String runId,LocalDateTime createdAt,LocalDateTime finishedAt){}
}
