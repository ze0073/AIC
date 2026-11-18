package com.shijie.accessibility.service;

import com.shijie.accessibility.ai.QwenGuideClient;
import com.shijie.accessibility.common.*;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import static com.shijie.accessibility.service.GuideWorkflowAdapter.*;

@Service
public class GuideService {
    static final Set<String> GOALS=Set.of("DEPLOY","CHECK","REPAIR","REPORT","HELP");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectAccessService access;
    private final GuideWorkflowAdapter adapter;
    private final QwenGuideClient qwen;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;

    public GuideService(JdbcTemplate jdbc,ObjectMapper json,ProjectAccessService access,GuideWorkflowAdapter adapter,
            QwenGuideClient qwen,IdempotencyService idempotency,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.adapter=adapter;this.qwen=qwen;this.idempotency=idempotency;
        this.tx=new TransactionTemplate(transactions);
    }
    private String actor(){return access.currentUser().id();}
    public Preferences preferences(){var rows=jdbc.query("SELECT * FROM guide_preferences WHERE user_id=?",(rs,n)->new Preferences(rs.getBoolean("welcome_seen"),rs.getBoolean("character_visible"),rs.getBoolean("voice_enabled"),qwen.available()),actor());return rows.isEmpty()?new Preferences(false,true,false,qwen.available()):rows.getFirst();}
    public Preferences preferences(PreferenceUpdate p){
        var old=preferences();boolean welcome=p.welcomeSeen()==null?old.welcomeSeen():p.welcomeSeen(),visible=p.characterVisible()==null?old.characterVisible():p.characterVisible(),voice=p.voiceEnabled()==null?old.voiceEnabled():p.voiceEnabled();
        jdbc.update("INSERT INTO guide_preferences(user_id,welcome_seen,character_visible,voice_enabled,updated_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE welcome_seen=VALUES(welcome_seen),character_visible=VALUES(character_visible),voice_enabled=VALUES(voice_enabled),updated_at=VALUES(updated_at)",actor(),welcome,visible,voice,LocalDateTime.now());return preferences();
    }
    public List<Session> list(){return jdbc.query("SELECT * FROM guide_sessions WHERE owner_id=? ORDER BY updated_at DESC,id DESC LIMIT 30",(rs,n)->session(rs),actor());}
    public Session create(Create p,String key){return tx.execute(status->{String operation="guide-create:"+actor();var previous=idempotency.find(key,operation);if(previous.isPresent())return get(previous.get());
        validateGoal(p.goal());validateAssistance(p.assistance());validateMode(p.mode(),p.aiConsent());Map<String,Object> context=adapter.validateContext(p.context()==null?Map.of():p.context());
        String id=Ulids.next();LocalDateTime now=LocalDateTime.now();jdbc.update("INSERT INTO guide_sessions(id,owner_id,goal,assistance,mode,ai_consent,status,context_json,draft_json,created_at,updated_at) VALUES(?,?,?,?,?,?,'ACTIVE',?,'{}',?,?)",id,actor(),p.goal(),p.assistance()==null?"GUIDED":p.assistance(),p.mode()==null?"RULE":p.mode(),p.aiConsent(),write(context),now,now);
        message(id,"ASSISTANT",welcome(p.goal()));idempotency.save(key,operation,id);preferences(new PreferenceUpdate(true,null,null));return get(id);});}
    public Session get(String id){Session s=owned(id);Map<String,Object> c=adapter.validateContext(s.context());Map<String,Object> resources=adapter.resources(c);return details(s,c,resources);}
    public Session patch(String id,Patch p){return tx.execute(status->{lock(id);Session s=owned(id);if(p.revision()!=s.revision())throw ApiException.conflict("GUIDE_SESSION_CHANGED","流程已在其他页面更新，请刷新后继续");
        String goal=p.goal()==null?s.goal():p.goal(),assistance=p.assistance()==null?s.assistance():p.assistance(),mode=p.mode()==null?s.mode():p.mode();boolean consent=p.aiConsent()==null?s.aiConsent():p.aiConsent();validateGoal(goal);validateAssistance(assistance);validateMode(mode,consent);
        Map<String,Object> c=p.context()==null?s.context():adapter.validateContext(p.context());Map<String,Object> draft=p.draft()==null?s.draft():safeDraft(p.draft());
        String state=p.status()==null?s.status():p.status();if(!Set.of("ACTIVE","COMPLETED").contains(state))throw ApiException.badRequest("GUIDE_STATUS_INVALID","无效流程状态");
        jdbc.update("UPDATE guide_sessions SET goal=?,assistance=?,mode=?,ai_consent=?,status=?,context_json=?,draft_json=?,revision=revision+1,updated_at=? WHERE id=?",goal,assistance,mode,consent,state,write(c),write(draft),LocalDateTime.now(),id);return get(id);});}

    /** Model calls and AI candidate generation happen before acquiring the confirmation transaction. */
    public Session send(String id,Send p,String key){
        Session before=owned(id);if(p.content()==null||p.content().isBlank()||p.content().length()>1000)throw ApiException.badRequest("GUIDE_MESSAGE_INVALID","请输入 1–1000 字的消息");
        String safe=SensitiveText.redact(p.content(),1000);boolean sensitive=!safe.equals(p.content().strip());
        String operation="guide-message:"+actor()+":"+id;var previous=idempotency.find(key,operation);if(previous.isPresent())return get(id);
        String reply;String goal=null;GuideWorkflowAdapter.Prepared prepared=null;
        if(sensitive)reply="已隐藏可能的凭据。请使用文件上传、仓库凭据或运行设置的专用表单，这条消息不会生成操作。";
        else if(p.actionType()!=null&&!p.actionType().isBlank()) {
            if(!"DELEGATED".equals(before.assistance()))throw ApiException.badRequest("GUIDE_ASSISTANCE_REQUIRED","请先切换到帮我办理");
            Map<String,Object> params=p.parameters()==null?Map.of():p.parameters();rejectSensitive(params);
            prepared=adapter.prepare(p.actionType(),params,adapter.validateContext(before.context()),before.aiConsent());
            reply="操作已经准备好。请核对卡片中的网站、版本和执行内容，再点击确认；聊天消息不会执行操作。";
        } else {
            goal=inferGoal(safe);reply=ruleReply(goal,safe);
            if("QWEN".equals(before.mode())&&before.aiConsent()) {
                try {var answer=qwen.interpret(modelContext(before,safe));goal=answer.goal();reply=reply+"\nQwen 下一步建议："+answer.advice();}
                catch(Exception ex){reply=reply+"\nQwen 暂时不可用，已继续使用规则流程；你的配置仍然保留。";}
            }
        }
        String finalReply=reply,finalGoal=goal;var finalPrepared=prepared;
        return tx.execute(status->{lock(id);Session current=owned(id);var duplicate=idempotency.find(key,operation);if(duplicate.isPresent())return get(id);
            if(current.revision()!=before.revision())throw ApiException.conflict("GUIDE_SESSION_CHANGED","流程在准备期间发生变化，请重新准备");
            message(id,"USER",safe);message(id,"ASSISTANT",finalReply);
            // Suggestions do not silently switch the user's active website or goal.
            if(finalGoal!=null&&!finalGoal.equals(current.goal()))message(id,"SYSTEM","建议选择目标："+welcome(finalGoal));
            if(finalPrepared!=null)saveAction(id,finalPrepared);
            jdbc.update("UPDATE guide_sessions SET revision=revision+1,updated_at=? WHERE id=?",LocalDateTime.now(),id);idempotency.save(key,operation,id);return get(id);
        });
    }

    public Session confirm(String actionId,String hash,String key,boolean reviewAccepted){try{return tx.execute(status->{
        Action action=ownedAction(actionId);lock(action.sessionId());jdbc.queryForList("SELECT id FROM guide_actions WHERE id=? FOR UPDATE",actionId);action=ownedAction(actionId);
        if(!Objects.equals(hash,action.actionHash()))throw ApiException.conflict("GUIDE_ACTION_CHANGED","操作预览已变化，请重新核对");
        String operation="guide-confirm:"+actor();var previous=idempotency.find(key,operation);
        if(previous.isPresent()&&!previous.get().equals(actionId))throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED","请求编号已经用于另一操作");
        if("SUCCEEDED".equals(action.status()))return get(action.sessionId());
        if(("PROPOSED".equals(action.status())||"EXPIRED".equals(action.status()))&&action.expiresAt().isBefore(LocalDateTime.now())){jdbc.update("UPDATE guide_actions SET status='EXPIRED' WHERE id=?",actionId);return get(action.sessionId());}
        if(!"PROPOSED".equals(action.status()))throw ApiException.conflict("GUIDE_ACTION_NOT_PENDING","操作已取消、失败或过期，请重新准备");
        if(!Objects.equals(action.guardHash(),adapter.guard(action.actionType(),action.arguments())))
            throw ApiException.conflict("GUIDE_RESOURCE_CHANGED","网站、源码、配置或任务状态已变化，请重新准备操作");
        if("APPLY_REPAIR".equals(action.actionType())&&!reviewAccepted)throw ApiException.badRequest("REPAIR_REVIEW_REQUIRED","请先勾选已审阅差异");
        try {
            // Failure rolls back business changes and receipt together; retries use an action-stable key.
            Map<String,Object> receipt=adapter.execute(action.actionType(),action.arguments(),"guide:"+action.id(),reviewAccepted);
            jdbc.update("UPDATE guide_actions SET status='SUCCEEDED',receipt_json=?,finished_at=? WHERE id=?",write(receipt),LocalDateTime.now(),actionId);
            Session s=owned(action.sessionId());Map<String,Object> c=new LinkedHashMap<>(s.context());
            for(String field:List.of("siteId","projectId","versionId","deploymentId","taskId","issueId","repairId","runId","snapshotId"))if(receipt.containsKey(field))c.put(field,receipt.get(field));
            c=adapter.validateContext(c);jdbc.update("UPDATE guide_sessions SET context_json=?,revision=revision+1,updated_at=? WHERE id=?",write(c),LocalDateTime.now(),s.id());
            message(s.id(),"SYSTEM","已执行并保存真实回执。任务状态将随业务进度更新。");idempotency.save(key,operation,actionId);return get(s.id());
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw ApiException.conflict("GUIDE_EXECUTION_FAILED",SensitiveText.redact(ex.getMessage(),500));}
    });}catch(RuntimeException ex){
        // The failed business transaction has rolled back. Record its outcome in a fresh transaction.
        if(!(ex instanceof ApiException api)||!Set.of("REPAIR_REVIEW_REQUIRED","GUIDE_ACTION_CHANGED","IDEMPOTENCY_KEY_REUSED","GUIDE_ACTION_NOT_PENDING","NOT_FOUND").contains(api.code())){
            tx.execute(status->{Action a=ownedAction(actionId);if("PROPOSED".equals(a.status())){
                String error=SensitiveText.redact(ex.getMessage(),500);
                jdbc.update("UPDATE guide_actions SET status='FAILED',error_message=?,receipt_json=?,finished_at=? WHERE id=? AND status='PROPOSED'",error,write(Map.of("status","FAILED","message",error)),LocalDateTime.now(),actionId);
                message(a.sessionId(),"SYSTEM","操作未完成："+error+"。请核对当前资源后重新准备。");
            }return null;});
        }
        throw ex;
    }}
    public Session cancel(String actionId,String key){return tx.execute(status->{Action action=ownedAction(actionId);lock(action.sessionId());jdbc.queryForList("SELECT id FROM guide_actions WHERE id=? FOR UPDATE",actionId);action=ownedAction(actionId);String operation="guide-cancel:"+actor();var previous=idempotency.find(key,operation);
        if(previous.isPresent()&&!previous.get().equals(actionId))throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED","请求编号已经用于另一操作");
        if("CANCELLED".equals(action.status()))return get(action.sessionId());if(!"PROPOSED".equals(action.status()))throw ApiException.conflict("GUIDE_ACTION_NOT_PENDING","操作已执行或失效");
        adapter.cancelPrepared(action.actionType(),action.arguments());
        jdbc.update("UPDATE guide_actions SET status='CANCELLED',finished_at=? WHERE id=?",LocalDateTime.now(),actionId);message(action.sessionId(),"SYSTEM","已取消待确认操作，没有启动业务执行。");idempotency.save(key,operation,actionId);return get(action.sessionId());});}

    private void saveAction(String session,GuideWorkflowAdapter.Prepared p){String id=Ulids.next();LocalDateTime now=LocalDateTime.now(),expires=now.plusMinutes(25);String arguments=write(p.arguments());String hash=Hashes.sha256(id+arguments+expires);
        jdbc.update("INSERT INTO guide_actions(id,session_id,action_type,status,arguments_json,preview_json,action_hash,guard_hash,expires_at,created_at) VALUES(?,?,?,'PROPOSED',?,?,?,?,?,?)",id,session,p.type(),arguments,write(p.preview()),hash,p.guardHash(),expires,now);}
    private Session owned(String id){var rows=jdbc.query("SELECT * FROM guide_sessions WHERE id=? AND owner_id=?",(rs,n)->session(rs),id,actor());if(rows.isEmpty())throw ApiException.notFound("向导流程不存在");return rows.getFirst();}
    private void lock(String id){if(jdbc.queryForList("SELECT id FROM guide_sessions WHERE id=? AND owner_id=? FOR UPDATE",id,actor()).isEmpty())throw ApiException.notFound("向导流程不存在");}
    private Action ownedAction(String id){var rows=jdbc.query("SELECT a.* FROM guide_actions a JOIN guide_sessions s ON s.id=a.session_id WHERE a.id=? AND s.owner_id=?",(rs,n)->action(rs),id,actor());if(rows.isEmpty())throw ApiException.notFound("向导操作不存在");return rows.getFirst();}
    private Session session(ResultSet rs)throws SQLException{return new Session(rs.getString("id"),rs.getString("goal"),rs.getString("assistance"),rs.getString("mode"),rs.getBoolean("ai_consent"),rs.getString("status"),read(rs.getString("context_json")),read(rs.getString("draft_json")),rs.getLong("revision"),rs.getTimestamp("updated_at").toLocalDateTime(),List.of(),List.of(),Map.of(),"SELECT",List.of());}
    private Action action(ResultSet rs)throws SQLException{String state=rs.getString("status");LocalDateTime expires=rs.getTimestamp("expires_at").toLocalDateTime();if("PROPOSED".equals(state)&&expires.isBefore(LocalDateTime.now()))state="EXPIRED";return new Action(rs.getString("id"),rs.getString("session_id"),rs.getString("action_type"),state,read(rs.getString("arguments_json")),read(rs.getString("preview_json")),rs.getString("action_hash"),rs.getString("guard_hash"),read(rs.getString("receipt_json")),rs.getString("error_message"),expires);}
    private Session details(Session s,Map<String,Object> c,Map<String,Object> resources){
        var messages=jdbc.query("SELECT * FROM (SELECT * FROM guide_messages WHERE session_id=? ORDER BY created_at DESC,id DESC LIMIT 60) m ORDER BY created_at,id",(rs,n)->new Message(rs.getString("id"),rs.getString("role"),rs.getString("content"),rs.getTimestamp("created_at").toLocalDateTime()),s.id());
        var actions=jdbc.query("SELECT * FROM guide_actions WHERE session_id=? ORDER BY created_at DESC,id DESC LIMIT 20",(rs,n)->action(rs),s.id());
        // Do not return internal guards or source candidate text through action arguments.
        actions=actions.stream().map(a->new Action(a.id(),a.sessionId(),a.actionType(),a.status(),Map.of(),a.preview(),a.actionHash(),null,a.receipt(),a.errorMessage(),a.expiresAt())).toList();
        String step=step(s.goal(),c,resources);return new Session(s.id(),s.goal(),s.assistance(),s.mode(),s.aiConsent(),s.status(),c,s.draft(),s.revision(),s.updatedAt(),messages,actions,resources,step,GOALS.stream().sorted().toList());
    }
    static String step(String goal,Map<String,Object> c,Map<String,Object> r){
        if("HELP".equals(goal))return "INTRODUCTION";
        if("DEPLOY".equals(goal)) {if(!c.containsKey("siteId"))return "SELECT_SITE";Map<?,?> site=asMap(r.get("site"));if(site.get("sourceVersionId")==null)return "IMPORT_SOURCE";if(!r.containsKey("deployment"))return "CONFIGURE";return "RESULTS";}
        if("CHECK".equals(goal))return c.containsKey("taskId")?"RESULTS":"SELECT_TEST";
        if("REPORT".equals(goal))return c.containsKey("snapshotId")?"REPORT_READY":c.containsKey("runId")?"REPORT_PREPARE":"SELECT_RUN";
        if(!c.containsKey("issueId"))return "SELECT_ISSUE";if(!c.containsKey("repairId"))return "PREPARE_REPAIR";
        Map<?,?> repair=asMap(r.get("repair"));return repair.get("newVersionId")==null?"REVIEW_REPAIR":"RETEST";
    }
    private static Map<?,?> asMap(Object value){if(value instanceof Map<?,?> map)return map;return value==null?Map.of():new ObjectMapper().convertValue(value,Map.class);}
    private Map<String,Object> safeDraft(Map<String,Object> raw){Map<String,Object> result=new LinkedHashMap<>();for(String key:List.of("name","purpose","path","language","candidateText","decorative","goal","config","testPolicy","suiteVersionId","repairMode"))if(raw.containsKey(key))result.put(key,raw.get(key));if(write(result).length()>16000)throw ApiException.badRequest("GUIDE_DRAFT_TOO_LARGE","表单内容过长");rejectSensitive(result);return result;}
    private Map<String,Object> modelContext(Session s,String content){Map<String,Object> result=new LinkedHashMap<>();result.put("message",content);result.put("goal",s.goal());List<String> ids=new ArrayList<>();List<Map<String,Object>> evidence=new ArrayList<>();
        var resources=adapter.resources(adapter.validateContext(s.context()));
        for(String key:List.of("run","deployment","repair","task")){Map<?,?> value=asMap(resources.get(key));if(value.get("id")!=null){String id=String.valueOf(value.get("id"));ids.add(id);Map<String,Object> item=new LinkedHashMap<>();item.put("id",id);item.put("kind",key);item.put("status",value.get("status"));if(value.get("issues") instanceof List<?> issues)item.put("issueCount",issues.size());evidence.add(item);}}
        result.put("evidenceIds",ids);result.put("evidence",evidence);return result;}
    static String inferGoal(String text){if(text.matches("(?s).*(修复|修正|改代码|补丁).*"))return "REPAIR";if(text.matches("(?s).*(报告|结果|导出).*"))return "REPORT";if(text.matches("(?s).*(部署|上传|托管|仓库).*"))return "DEPLOY";if(text.matches("(?s).*(检测|检查|体检|性能|安全).*"))return "CHECK";return "HELP";}
    private String ruleReply(String goal,String content){if(content.matches("\\s*(确认|开始|执行|开始执行)\\s*"))return "请在具体操作卡片中点击确认执行。你可以先选择目标，或者继续当前流程。";return welcome(goal)+" 当前已保存你的选择。请选择相应目标卡片，或使用下面的流程卡继续操作。";}
    private static String welcome(String goal){return switch(goal){case "DEPLOY"->"我可以带你创建网站工作区、导入源码、确认设置并部署检测。";case "CHECK"->"我可以帮你选择已登记网站和检查方案，准备计划并跟踪真实检测结果。";case "REPAIR"->"我可以解释问题，准备修复候选，带你检查差异并跟踪复测。";case "REPORT"->"我可以带你读懂检测证据、生成固定报告快照并导出。";default->"你好，我是视界向导。请选择部署、检测、修复、报告或功能介绍，我会陪你完成流程。";};}
    private void message(String id,String role,String content){jdbc.update("INSERT INTO guide_messages(id,session_id,role,content,created_at) VALUES(?,?,?,?,?)",Ulids.next(),id,role,SensitiveText.redact(content,4000),LocalDateTime.now());}
    private void validateGoal(String goal){if(goal==null||!GOALS.contains(goal))throw ApiException.badRequest("GUIDE_GOAL_INVALID","请选择五类目标之一");}
    private void validateAssistance(String assistance){if(assistance!=null&&!Set.of("GUIDED","DELEGATED").contains(assistance))throw ApiException.badRequest("GUIDE_ASSISTANCE_INVALID","请选择带我操作或帮我办理");}
    private void validateMode(String mode,boolean consent){if(mode!=null&&!Set.of("RULE","QWEN").contains(mode))throw ApiException.badRequest("GUIDE_MODE_INVALID","请选择规则或 Qwen 模式");if("QWEN".equals(mode)&&!consent)throw ApiException.badRequest("AI_CONSENT_REQUIRED","请先同意发送脱敏摘要");}
    private String write(Object value){return json.writeValueAsString(value);}
    @SuppressWarnings("unchecked") private Map<String,Object> read(String value){return value==null?Map.of():json.readValue(value,Map.class);}
    public record Preferences(boolean welcomeSeen,boolean characterVisible,boolean voiceEnabled,boolean qwenAvailable){}
    public record PreferenceUpdate(Boolean welcomeSeen,Boolean characterVisible,Boolean voiceEnabled){}
    public record Create(String goal,String assistance,String mode,boolean aiConsent,Map<String,Object> context){}
    public record Patch(long revision,String goal,String assistance,String mode,Boolean aiConsent,String status,Map<String,Object> context,Map<String,Object> draft){}
    public record Send(String content,String actionType,Map<String,Object> parameters){}
    public record Message(String id,String role,String content,LocalDateTime createdAt){}
    public record Action(String id,String sessionId,String actionType,String status,Map<String,Object> arguments,Map<String,Object> preview,String actionHash,String guardHash,Map<String,Object> receipt,String errorMessage,LocalDateTime expiresAt){}
    public record Session(String id,String goal,String assistance,String mode,boolean aiConsent,String status,Map<String,Object> context,Map<String,Object> draft,long revision,LocalDateTime updatedAt,List<Message> messages,List<Action> actions,Map<String,Object> resources,String step,List<String> suggestedGoals){}
}
