package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.SensitiveText;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ProjectAssistantService {
    private static final Set<String> INTENTS=Set.of("HELP","PROJECT_OVERVIEW","PREPARE_AGENT_TASK","TASK_STATUS");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectAccessService access;
    private final OnlineTargetService targets;
    private final TestSuiteService suites;
    private final AgentTaskService agents;
    private final IdempotencyService idempotency;

    public ProjectAssistantService(JdbcTemplate jdbc,ObjectMapper json,ProjectAccessService access,
                                   OnlineTargetService targets,TestSuiteService suites,AgentTaskService agents,
                                   IdempotencyService idempotency) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.targets=targets;this.suites=suites;
        this.agents=agents;this.idempotency=idempotency;
    }

    @Transactional
    public SessionView create(String projectId,String requestedMode,boolean aiConsent) {
        access.require(projectId,"VIEWER");
        String mode=normalizeMode(requestedMode);
        if("QWEN".equals(mode)&&!aiConsent)
            throw ApiException.badRequest("ASSISTANT_AI_CONSENT_REQUIRED","Qwen 项目助手需要先确认脱敏数据发送范围");
        String id=Ulids.next();LocalDateTime now=LocalDateTime.now();String actor=access.currentUser().id();
        jdbc.update("INSERT INTO project_assistant_sessions(id,project_id,created_by,mode,ai_consent,status,title,created_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE',?,?,?)",
                id,projectId,actor,mode,aiConsent,mode.equals("RULE")?"规则项目助手":"Qwen 项目助手",now,now);
        addMessage(id,"ASSISTANT","告诉我你想检查什么。我可以查看项目概况、生成检测计划或查询任务状态；任何执行都需要你在计划卡片上单独确认。",
                Map.of("schemaVersion","0.8.0","supportedIntents",INTENTS),now);
        return loadAccessible(id,true);
    }

    public List<SessionView> list(String projectId) {
        access.require(projectId,"VIEWER");
        String actor=access.currentUser().id();
        return jdbc.query("SELECT s.* FROM project_assistant_sessions s JOIN project_members m ON m.project_id=s.project_id AND m.user_id=? WHERE s.project_id=? ORDER BY s.updated_at DESC LIMIT 30",
                this::mapSession,actor,projectId).stream().map(value->withDetails(value,false)).toList();
    }

    public Map<String,Object> recommendation(String projectId) {
        access.require(projectId,"VIEWER");
        List<OnlineTargetService.TargetView> targetList=targets.list(projectId);
        List<TestSuiteService.SuiteView> suiteList=suites.list(projectId);
        OnlineTargetService.TargetView target=targetList.isEmpty()?null:targetList.getFirst();
        TestSuiteService.SuiteView suite=suiteList.isEmpty()?null:suiteList.getFirst();
        List<Map<String,Object>> baselineList=target==null||suite==null?List.of():agents.baselines(projectId,target.id(),suite.currentVersionId());
        Map<String,Object> baseline=baselineList.isEmpty()?Map.of():baselineList.getFirst();
        List<String> warnings=new ArrayList<>();
        if(target==null)warnings.add("还没有登记可检查的网站");
        if(suite==null)warnings.add("还没有创建检查方案");
        if(target!=null&&suite!=null&&baseline.isEmpty())warnings.add("暂无同范围历史结果，本次完成后将成为首个趋势基线");
        Map<String,Object> result=new LinkedHashMap<>();result.put("schemaVersion","0.8.0");
        result.put("ready",target!=null&&suite!=null);result.put("warnings",warnings);
        result.put("defaultGoal","完成一次日常网站体检，检查页面体验和只读接口权限，并用通俗语言说明结果与下一步。");
        if(target!=null)result.put("target",Map.of("id",target.id(),"name",target.name(),"baseUrl",target.baseUrl(),
                "reason",targetList.size()==1?"项目中唯一已登记的网站":"最近更新的已登记网站"));
        if(suite!=null)result.put("suite",Map.of("id",suite.id(),"versionId",suite.currentVersionId(),"name",suite.name(),
                "version",suite.currentVersion(),"reason",suiteList.size()==1?"项目中唯一可用的检查方案":"最近更新的检查方案"));
        if(!baseline.isEmpty()){
            Map<String,Object> selected=new LinkedHashMap<>(baseline);selected.put("reason","最近一次具有完整模块证据的同范围任务");result.put("baseline",selected);
        }
        return result;
    }

    public SessionView get(String sessionId) { return loadAccessible(sessionId,true); }

    @Transactional
    public MessageResult message(String sessionId,MessageCommand command,String key) {
        SessionView session=loadAccessible(sessionId,false);
        requireCreator(session);
        // Serialize messages for one conversation so sequence numbers and
        // idempotency checks remain stable when the UI retries quickly.
        jdbc.queryForObject("SELECT id FROM project_assistant_sessions WHERE id=? FOR UPDATE",String.class,sessionId);
        Optional<String> previous=idempotency.find(key,"assistant-message");
        if(previous.isPresent())return replayMessage(sessionId,previous.get());
        if(!"ACTIVE".equals(session.status()))throw ApiException.conflict("ASSISTANT_SESSION_CLOSED","项目助手会话已结束");
        String raw=Objects.toString(command.content(),"").strip();
        if(raw.isBlank()||raw.length()>1000)throw ApiException.badRequest("ASSISTANT_MESSAGE_INVALID","消息必须为 1–1000 字");
        String safeContent=SensitiveText.redact(raw,1000);
        String rawGoal=Objects.toString(command.goal(),"").strip();
        String safeGoal=SensitiveText.redact(rawGoal,1000);
        LocalDateTime now=LocalDateTime.now();
        MessageView user=addMessage(sessionId,"USER",safeContent,Map.of("intent",Objects.toString(command.intent(),"")),now);
        MessageView reply;ActionView action=null;
        if(!safeContent.equals(raw)||!safeGoal.equals(rawGoal)) {
            reply=addMessage(sessionId,"ASSISTANT","我检测到并已隐藏 Token、Cookie、密码或密钥类内容，因此没有生成操作。请到测试中心的专用凭据表单登记凭据，再回来选择已登记配置。",
                    Map.of("intent","SENSITIVE_INPUT_REJECTED","actionCreated",false),LocalDateTime.now());
        } else {
            String intent=normalizeIntent(command.intent(),safeContent);
            switch(intent) {
                case "PROJECT_OVERVIEW" -> {
                    Map<String,Object> overview=overview(session.projectId());
                    reply=addMessage(sessionId,"ASSISTANT",overviewText(overview),Map.of("intent",intent,"evidence",overview),LocalDateTime.now());
                }
                case "TASK_STATUS" -> {
                    Map<String,Object> status=taskStatus(session,command.taskId());
                    reply=addMessage(sessionId,"ASSISTANT",String.valueOf(status.get("message")),Map.of("intent",intent,"evidence",status),LocalDateTime.now());
                }
                case "PREPARE_AGENT_TASK" -> {
                    access.require(session.projectId(),"MEMBER");
                    if(command.mode()!=null&&!command.mode().isBlank()&&!session.mode().equals(normalizeMode(command.mode())))
                        throw ApiException.badRequest("ASSISTANT_MODE_CHANGED","会话模式不能在生成计划时更改，请新建会话");
                    if(command.targetId()==null||command.targetId().isBlank()||command.suiteVersionId()==null||command.suiteVersionId().isBlank()
                            ||safeGoal.isBlank())
                        throw ApiException.badRequest("ASSISTANT_SCOPE_REQUIRED","请选择已登记目标、测试套件版本并填写检测目标");
                    AgentTaskService.TaskView draft=agents.createDraft(session.projectId(),command.targetId(),command.suiteVersionId(),safeGoal,
                            session.aiConsent(),session.mode(),blankToNull(command.baselineTaskId()));
                    reply=addMessage(sessionId,"ASSISTANT","计划已经生成并冻结，但尚未执行。请核对下方计划卡片；只有点击“确认执行”才会启动检测。",
                            Map.of("intent",intent,"agentTaskId",draft.id(),"actionCreated",true),LocalDateTime.now());
                    action=createStartAction(session,user.id(),draft);
                }
                default -> reply=addMessage(sessionId,"ASSISTANT",helpText(safeContent),Map.of("intent","HELP","actionCreated",false),LocalDateTime.now());
            }
        }
        jdbc.update("UPDATE project_assistant_sessions SET updated_at=? WHERE id=?",LocalDateTime.now(),sessionId);
        idempotency.save(key,"assistant-message",user.id());
        return new MessageResult(user,reply,action);
    }

    @Transactional
    public ActionView confirm(String actionId,String submittedHash,String key) {
        ActionView action=loadActionAccessible(actionId);
        access.require(action.projectId(),action.requiredRole());requireActionCreator(action);
        Optional<String> previous=idempotency.find(key,"confirm-assistant-action");
        if(previous.isPresent()){
            if(!actionId.equals(previous.get()))throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED","该请求标识已用于其他助手操作");
            return loadActionAccessible(actionId);
        }
        if(!secureEquals(action.actionHash(),submittedHash))throw ApiException.conflict("ASSISTANT_ACTION_CHANGED","操作校验不一致，请重新查看计划");
        if(action.expiresAt().isBefore(LocalDateTime.now())){
            jdbc.update("UPDATE project_assistant_actions SET status='EXPIRED',finished_at=? WHERE id=? AND status='PROPOSED'",LocalDateTime.now(),actionId);
            return loadActionAccessible(actionId);
        }
        if(!"PROPOSED".equals(action.status()))throw ApiException.conflict("ASSISTANT_ACTION_NOT_PENDING","该操作已确认、取消或失效");
        int claimed=jdbc.update("UPDATE project_assistant_actions SET status='EXECUTING',confirmed_by=?,confirmed_at=? WHERE id=? AND status='PROPOSED'",
                access.currentUser().id(),LocalDateTime.now(),actionId);
        if(claimed!=1)throw ApiException.conflict("ASSISTANT_ACTION_NOT_PENDING","该操作已由另一请求处理");
        Map<String,Object> args=action.arguments();
        try {
            AgentTaskService.TaskView task=agents.start(String.valueOf(args.get("taskId")),String.valueOf(args.get("planHash")),"assistant:"+actionId);
            Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("taskId",task.id());receipt.put("status",task.status());
            receipt.put("detailPath","/agent-tasks/"+task.id());receipt.put("confirmedAt",LocalDateTime.now().toString());
            jdbc.update("UPDATE project_assistant_actions SET status='SUCCEEDED',receipt_json=?,finished_at=? WHERE id=?",
                    write(receipt),LocalDateTime.now(),actionId);
            addMessage(action.sessionId(),"SYSTEM","已通过独立确认启动真实智能体任务。",Map.of("taskId",task.id(),"status",task.status()),LocalDateTime.now());
            jdbc.update("UPDATE project_assistant_sessions SET updated_at=? WHERE id=?",LocalDateTime.now(),action.sessionId());
            idempotency.save(key,"confirm-assistant-action",actionId);
        } catch(Exception ex) {
            String error=SensitiveText.redact(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage(),500);
            jdbc.update("UPDATE project_assistant_actions SET status='FAILED',error_message=?,receipt_json=?,finished_at=? WHERE id=?",
                    error,write(Map.of("status","FAILED","message",error)),LocalDateTime.now(),actionId);
        }
        return loadActionAccessible(actionId);
    }

    @Transactional
    public ActionView cancel(String actionId,String key) {
        ActionView action=loadActionAccessible(actionId);
        access.require(action.projectId(),"MEMBER");requireActionCreator(action);
        Optional<String> previous=idempotency.find(key,"cancel-assistant-action");
        if(previous.isPresent()){
            if(!actionId.equals(previous.get()))throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED","该请求标识已用于其他助手操作");
            return loadActionAccessible(actionId);
        }
        if(!"PROPOSED".equals(action.status()))throw ApiException.conflict("ASSISTANT_ACTION_NOT_PENDING","该操作已确认、取消或失效");
        int changed=jdbc.update("UPDATE project_assistant_actions SET status='CANCELLED',confirmed_by=?,confirmed_at=?,finished_at=?,receipt_json=? WHERE id=? AND status='PROPOSED'",
                access.currentUser().id(),LocalDateTime.now(),LocalDateTime.now(),write(Map.of("status","CANCELLED","message","用户取消了待确认操作")),actionId);
        if(changed!=1)throw ApiException.conflict("ASSISTANT_ACTION_NOT_PENDING","该操作已由另一请求处理");
        String taskId=Objects.toString(action.arguments().get("taskId"),"");
        if(!taskId.isBlank())agents.cancel(taskId);
        addMessage(action.sessionId(),"SYSTEM","已取消待确认计划，没有启动检测。",Map.of("taskId",taskId),LocalDateTime.now());
        jdbc.update("UPDATE project_assistant_sessions SET updated_at=? WHERE id=?",LocalDateTime.now(),action.sessionId());
        idempotency.save(key,"cancel-assistant-action",actionId);
        return loadActionAccessible(actionId);
    }

    private ActionView createStartAction(SessionView session,String sourceMessageId,AgentTaskService.TaskView draft) {
        String id=Ulids.next(),actor=access.currentUser().id();LocalDateTime now=LocalDateTime.now(),expires=now.plusMinutes(25);
        Map<String,Object> arguments=new LinkedHashMap<>();arguments.put("projectId",session.projectId());arguments.put("sessionId",session.id());
        arguments.put("taskId",draft.id());arguments.put("planHash",draft.planHash());arguments.put("proposedBy",actor);
        arguments.put("expiresAt",expires.toString());
        Map<String,Object> preview=new LinkedHashMap<>();preview.put("taskId",draft.id());preview.put("goal",draft.goal());
        preview.put("plan",draft.plan());preview.put("planHash",draft.planHash());preview.put("status",draft.status());
        String hash=sha256(write(arguments));
        jdbc.update("INSERT INTO project_assistant_actions(id,session_id,source_message_id,action_type,required_role,status,arguments_json,preview_json,action_hash,expires_at,proposed_by,resource_type,resource_id,created_at) VALUES (?,?,?,'START_AGENT_TASK','MEMBER','PROPOSED',?,?,?,?,?,'AGENT_TASK',?,?)",
                id,session.id(),sourceMessageId,write(arguments),write(preview),hash,expires,actor,draft.id(),now);
        return loadActionAccessible(id);
    }

    private Map<String,Object> overview(String projectId) {
        List<OnlineTargetService.TargetView> targetList=targets.list(projectId);
        List<TestSuiteService.SuiteView> suiteList=suites.list(projectId);
        List<AgentTaskService.TaskView> tasks=agents.list(projectId);
        Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM run_issues i JOIN test_runs r ON r.id=i.run_id WHERE r.project_id=? AND i.review_status='PENDING_REVIEW'",Integer.class,projectId);
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("targets",targetList.stream().map(item->Map.of("id",item.id(),"name",item.name(),"baseUrl",item.baseUrl())).toList());
        value.put("suites",suiteList.stream().map(item->Map.of("id",item.id(),"name",item.name(),"version",item.currentVersion(),"versionId",item.currentVersionId())).toList());
        value.put("recentTasks",tasks.stream().limit(5).map(item->Map.of("id",item.id(),"goal",item.goal(),"status",item.status())).toList());
        value.put("pendingReviewCount",pending==null?0:pending);return value;
    }

    private String overviewText(Map<String,Object> overview) {
        int targetCount=items(overview.get("targets")).size(),suiteCount=items(overview.get("suites")).size(),taskCount=items(overview.get("recentTasks")).size();
        return "当前项目有 "+targetCount+" 个已登记在线目标、"+suiteCount+" 个测试套件，最近可见 "+taskCount+" 个智能体任务；未自动确认候选 "+overview.get("pendingReviewCount")+" 个。生成计划前请在右侧明确选择目标和套件。";
    }

    private Map<String,Object> taskStatus(SessionView session,String requestedTaskId) {
        String taskId=blankToNull(requestedTaskId);
        if(taskId==null)taskId=jdbc.query("SELECT resource_id FROM project_assistant_actions WHERE session_id=? AND resource_type='AGENT_TASK' ORDER BY created_at DESC LIMIT 1",
                rs->rs.next()?rs.getString(1):null,session.id());
        if(taskId==null)return Map.of("message","这个会话还没有关联智能体任务。请先生成计划并通过操作卡片确认。","status","NONE");
        AgentTaskService.TaskView task=agents.get(taskId);
        if(!session.projectId().equals(task.projectId()))throw ApiException.notFound("智能体任务不存在");
        return Map.of("message","任务 "+task.id()+" 当前状态："+task.status()+"。已取得的结果和未执行项请打开任务详情查看。",
                "taskId",task.id(),"status",task.status(),"summary",task.summary());
    }

    private String helpText(String content) {
        if(containsAny(content,"确认","开始","执行","可以","同意"))
            return "聊天中的“确认”不会执行任何操作。请先在右侧选择已登记目标、套件和检测目标，生成计划后，再点击计划卡片上的“确认执行”。";
        return "我目前可以：查看项目概况、按已登记目标和套件生成智能检测计划、查询任务状态。凭据、主动扫描、上传写测试和修复操作仍需到对应页面完成；漏洞候选不会由 AI 自动确认，可按需记录审阅结论。";
    }

    private String normalizeIntent(String requested,String content) {
        if(requested!=null&&!requested.isBlank()){
            String value=requested.strip().toUpperCase(Locale.ROOT);
            if(!INTENTS.contains(value))throw ApiException.badRequest("ASSISTANT_INTENT_INVALID","项目助手不支持该操作");
            return value;
        }
        if(containsAny(content,"确认","开始","执行","可以","同意"))return "HELP";
        if(containsAny(content,"状态","进度","怎么样","完成了吗"))return "TASK_STATUS";
        if(containsAny(content,"概况","概览","有哪些目标","有哪些套件","项目情况"))return "PROJECT_OVERVIEW";
        return "HELP";
    }

    private MessageResult replayMessage(String sessionId,String messageId) {
        List<MessageView> messages=messages(sessionId);
        MessageView user=messages.stream().filter(item->item.id().equals(messageId)).findFirst()
                .orElseThrow(()->ApiException.conflict("IDEMPOTENCY_KEY_REUSED","该请求标识已用于其他会话"));
        if(!"USER".equals(user.role()))throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED","该请求标识已用于其他操作");
        MessageView reply=messages.stream().filter(item->item.seqNo()==user.seqNo()+1).findFirst().orElse(null);
        ActionView action=actions(sessionId).stream().filter(item->messageId.equals(item.sourceMessageId())).findFirst().orElse(null);
        return new MessageResult(user,reply,action);
    }

    private SessionView loadAccessible(String id,boolean details) {
        String actor=access.currentUser().id();
        List<SessionView> rows=jdbc.query("SELECT s.* FROM project_assistant_sessions s JOIN project_members m ON m.project_id=s.project_id AND m.user_id=? WHERE s.id=?",
                this::mapSession,actor,id);
        if(rows.isEmpty())throw ApiException.notFound("项目助手会话不存在");
        return withDetails(rows.getFirst(),details);
    }

    private SessionView withDetails(SessionView value,boolean details) {
        return new SessionView(value.id(),value.projectId(),value.createdBy(),value.mode(),value.aiConsent(),value.status(),value.title(),
                value.createdAt(),value.updatedAt(),details?messages(value.id()):List.of(),details?actions(value.id()):List.of(),
                List.of("帮我查看项目概况","帮我检查页面质量和加载速度","查询这个会话的任务状态"));
    }

    private SessionView mapSession(java.sql.ResultSet rs,int n)throws java.sql.SQLException {
        return new SessionView(rs.getString("id"),rs.getString("project_id"),rs.getString("created_by"),rs.getString("mode"),
                rs.getBoolean("ai_consent"),rs.getString("status"),rs.getString("title"),
                rs.getTimestamp("created_at").toLocalDateTime(),rs.getTimestamp("updated_at").toLocalDateTime(),List.of(),List.of(),List.of());
    }

    private MessageView addMessage(String sessionId,String role,String content,Map<String,Object> metadata,LocalDateTime now) {
        Integer next=jdbc.queryForObject("SELECT COALESCE(MAX(seq_no),0)+1 FROM project_assistant_messages WHERE session_id=?",Integer.class,sessionId);
        String id=Ulids.next();int sequence=next==null?1:next;
        jdbc.update("INSERT INTO project_assistant_messages(id,session_id,seq_no,role,content,metadata_json,created_at) VALUES (?,?,?,?,?,?,?)",
                id,sessionId,sequence,role,SensitiveText.redact(content,4000),write(SensitiveText.redactValues(metadata)),now);
        return new MessageView(id,sequence,role,SensitiveText.redact(content,4000),metadata,now);
    }

    private List<MessageView> messages(String sessionId) {
        return jdbc.query("SELECT * FROM project_assistant_messages WHERE session_id=? ORDER BY seq_no",(rs,n)->new MessageView(rs.getString("id"),
                rs.getInt("seq_no"),rs.getString("role"),rs.getString("content"),read(rs.getString("metadata_json")),
                rs.getTimestamp("created_at").toLocalDateTime()),sessionId);
    }

    private List<ActionView> actions(String sessionId) {
        return jdbc.query("SELECT a.*,s.project_id FROM project_assistant_actions a JOIN project_assistant_sessions s ON s.id=a.session_id WHERE a.session_id=? ORDER BY a.created_at",
                this::mapAction,sessionId);
    }

    private ActionView loadActionAccessible(String id) {
        String actor=access.currentUser().id();
        List<ActionView> rows=jdbc.query("SELECT a.*,s.project_id FROM project_assistant_actions a JOIN project_assistant_sessions s ON s.id=a.session_id JOIN project_members m ON m.project_id=s.project_id AND m.user_id=? WHERE a.id=?",
                this::mapAction,actor,id);
        if(rows.isEmpty())throw ApiException.notFound("项目助手操作不存在");
        ActionView value=rows.getFirst();
        if("PROPOSED".equals(value.status())&&value.expiresAt().isBefore(LocalDateTime.now()))
            return new ActionView(value.id(),value.sessionId(),value.projectId(),value.sourceMessageId(),value.actionType(),value.requiredRole(),"EXPIRED",
                    value.arguments(),value.preview(),value.actionHash(),value.expiresAt(),value.proposedBy(),value.confirmedBy(),value.resourceType(),value.resourceId(),
                    value.receipt(),value.errorMessage(),value.createdAt(),value.confirmedAt(),value.finishedAt());
        return value;
    }

    private ActionView mapAction(java.sql.ResultSet rs,int n)throws java.sql.SQLException {
        return new ActionView(rs.getString("id"),rs.getString("session_id"),rs.getString("project_id"),rs.getString("source_message_id"),
                rs.getString("action_type"),rs.getString("required_role"),rs.getString("status"),read(rs.getString("arguments_json")),
                read(rs.getString("preview_json")),rs.getString("action_hash"),rs.getTimestamp("expires_at").toLocalDateTime(),
                rs.getString("proposed_by"),rs.getString("confirmed_by"),rs.getString("resource_type"),rs.getString("resource_id"),
                read(rs.getString("receipt_json")),rs.getString("error_message"),rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("confirmed_at")==null?null:rs.getTimestamp("confirmed_at").toLocalDateTime(),
                rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toLocalDateTime());
    }

    private void requireCreator(SessionView session) {
        if(!Objects.equals(session.createdBy(),access.currentUser().id()))throw ApiException.forbidden("只有会话创建者可以继续该对话");
    }
    private void requireActionCreator(ActionView action) {
        if(!Objects.equals(action.proposedBy(),access.currentUser().id()))throw ApiException.forbidden("只有操作创建者可以确认或取消");
    }
    private String normalizeMode(String value){String mode=value==null?"RULE":value.strip().toUpperCase(Locale.ROOT);if(!Set.of("RULE","QWEN").contains(mode))throw ApiException.badRequest("ASSISTANT_MODE_INVALID","请选择 RULE 或 QWEN 模式");return mode;}
    private String blankToNull(String value){return value==null||value.isBlank()?null:value.strip();}
    private boolean containsAny(String text,String... tokens){String value=text==null?"":text.toLowerCase(Locale.ROOT);for(String token:tokens)if(value.contains(token.toLowerCase(Locale.ROOT)))return true;return false;}
    private boolean secureEquals(String expected,String actual){if(expected==null||actual==null)return false;return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    @SuppressWarnings("unchecked") private Map<String,Object> read(String value){if(value==null)return Map.of();try{return (Map<String,Object>)json.readValue(value,Map.class);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private List<?> items(Object value){return value instanceof List<?> list?list:List.of();}

    public record MessageCommand(String content,String intent,String targetId,String suiteVersionId,String baselineTaskId,
                                 String goal,String mode,Boolean aiConsent,String taskId) {}
    public record SessionView(String id,String projectId,String createdBy,String mode,boolean aiConsent,String status,String title,
                              LocalDateTime createdAt,LocalDateTime updatedAt,List<MessageView> messages,List<ActionView> actions,
                              List<String> suggestions) {}
    public record MessageView(String id,int seqNo,String role,String content,Map<String,Object> metadata,LocalDateTime createdAt) {}
    public record ActionView(String id,String sessionId,String projectId,String sourceMessageId,String actionType,String requiredRole,
                             String status,Map<String,Object> arguments,Map<String,Object> preview,String actionHash,LocalDateTime expiresAt,
                             String proposedBy,String confirmedBy,String resourceType,String resourceId,Map<String,Object> receipt,
                             String errorMessage,LocalDateTime createdAt,LocalDateTime confirmedAt,LocalDateTime finishedAt) {}
    public record MessageResult(MessageView userMessage,MessageView assistantMessage,ActionView action) {}
}
