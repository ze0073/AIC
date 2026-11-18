package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class CollaborationService {
    private static final List<String> TASK_STATUSES=List.of("OPEN","IN_PROGRESS","READY_FOR_RETEST","VERIFIED","REOPENED");
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final ProjectAccessService access; private final IdempotencyService idempotency;
    public CollaborationService(JdbcTemplate jdbc,ObjectMapper json,ProjectAccessService access,IdempotencyService idempotency){this.jdbc=jdbc;this.json=json;this.access=access;this.idempotency=idempotency;}

    public List<Map<String,Object>> issues(String projectId,String status,String severity){
        access.require(projectId,"VIEWER");StringBuilder sql=new StringBuilder("SELECT i.*,u.display_name assignee_name FROM run_issues i LEFT JOIN app_users u ON u.id=i.assignee_id WHERE i.project_id=?");List<Object>args=new ArrayList<>();args.add(projectId);
        if(status!=null&&!status.isBlank()){sql.append(" AND i.task_status=?");args.add(status);}if(severity!=null&&!severity.isBlank()){sql.append(" AND i.severity=?");args.add(severity);}sql.append(" ORDER BY FIELD(i.severity,'HIGH','MEDIUM','LOW'),i.updated_at DESC");
        return jdbc.query(sql.toString(),(rs,n)->row(rs),args.toArray());
    }
    public Map<String,Object> issue(String issueId){Map<String,Object>issue=rawIssue(issueId);access.require(String.valueOf(issue.get("projectId")),"VIEWER");issue.put("comments",comments(issueId));issue.put("history",history(issueId));return issue;}

    @Transactional
    public Map<String,Object> reviewIssue(String issueId,String decision,String reason,String key){
        Map<String,Object>issue=rawIssue(issueId);access.require(String.valueOf(issue.get("projectId")),"MEMBER");
        var previous=idempotency.find(key,"review-run-issue");if(previous.isPresent()){
            if(!issueId.equals(previous.get()))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","幂等键已用于另一问题的审阅");
            return issue(issueId);
        }
        String normalized=decision==null?"":decision.toUpperCase(Locale.ROOT);
        if(!Set.of("CONFIRMED","DISMISSED").contains(normalized))throw ApiException.badRequest("INVALID_REVIEW_DECISION","审阅结论只能为记录风险或驳回候选");
        String explanation=reason==null?"":reason.strip();
        if(explanation.isBlank()||explanation.length()>2000)throw ApiException.badRequest("REVIEW_REASON_REQUIRED","审阅理由须为 1–2000 字");
        String old=String.valueOf(issue.get("reviewStatus"));
        jdbc.update("UPDATE run_issues SET review_status=?,updated_at=? WHERE id=?",normalized,LocalDateTime.now(),issueId);
        history(issueId,"REVIEWED",old,normalized,Map.of("reason",explanation));
        idempotency.save(key,"review-run-issue",issueId);
        return issue(issueId);
    }

    @Transactional
    public Map<String,Object> updateIssue(String issueId,String status,String assigneeId,String retestRunId,String key){
        var previous=idempotency.find(key,"update-run-issue");if(previous.isPresent())return issue(previous.get());
        Map<String,Object> issue=rawIssue(issueId);String projectId=String.valueOf(issue.get("projectId"));access.require(projectId,"MEMBER");String normalized=status==null?String.valueOf(issue.get("taskStatus")):status.toUpperCase();if(!TASK_STATUSES.contains(normalized))throw ApiException.badRequest("INVALID_TASK_STATUS","修复任务状态无效");
        String old=String.valueOf(issue.get("taskStatus"));
        if("VERIFIED".equals(normalized))validateRetest(issue,retestRunId);
        if(assigneeId!=null&&!assigneeId.isBlank()){Integer member=jdbc.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=?",Integer.class,projectId,assigneeId);if(member==null||member==0)throw ApiException.badRequest("ASSIGNEE_NOT_MEMBER","负责人不是项目成员");}
        jdbc.update("UPDATE run_issues SET task_status=?,assignee_id=COALESCE(?,assignee_id),retest_run_id=?,updated_at=? WHERE id=?",normalized,blankToNull(assigneeId),blankToNull(retestRunId),LocalDateTime.now(),issueId);
        history(issueId,"STATUS_CHANGED",old,normalized,Map.of("retestRunId",Objects.toString(retestRunId,"")));
        idempotency.save(key,"update-run-issue",issueId);return issue(issueId);
    }

    @Transactional
    public Map<String,Object> comment(String issueId,String body){Map<String,Object>issue=rawIssue(issueId);access.require(String.valueOf(issue.get("projectId")),"MEMBER");String id=Ulids.next();jdbc.update("INSERT INTO issue_comments(id,issue_id,user_id,body,created_at) VALUES (?,?,?,?,?)",id,issueId,access.currentUser().id(),body.strip(),LocalDateTime.now());history(issueId,"COMMENTED",null,null,Map.of("commentId",id));return issue(issueId);}

    @Transactional
    public Map<String,Object> createCase(String projectId,String issueId,String title,String category,List<String>tags,Map<String,Object>content){
        access.require(projectId,"MEMBER");if(issueId!=null&&!issueId.isBlank()&&!projectId.equals(String.valueOf(rawIssue(issueId).get("projectId"))))throw ApiException.notFound("修复任务不存在");String id=Ulids.next();LocalDateTime now=LocalDateTime.now();jdbc.update("INSERT INTO knowledge_cases(id,project_id,issue_id,status,title,category,tags,content,created_by,created_at,updated_at) VALUES (?,?,?,'DRAFT',?,?,?,?,?,?,?)",id,projectId,blankToNull(issueId),title.strip(),blankToNull(category),write(tags==null?List.of():tags),write(content),access.currentUser().id(),now,now);return knowledge(id);
    }
    @Transactional
    public Map<String,Object> publishCase(String id){Map<String,Object>item=knowledge(id);String projectId=String.valueOf(item.get("projectId"));access.require(projectId,"MEMBER");String issueId=Objects.toString(item.get("issueId"),"");if(issueId.isBlank()||!"VERIFIED".equals(String.valueOf(rawIssue(issueId).get("taskStatus"))))throw ApiException.conflict("ISSUE_NOT_VERIFIED","案例必须关联完成有效复测的任务");Object content=item.get("content");if(!(content instanceof Map<?,?> details)||!hasText(details,"cause")||!hasText(details,"fix")||!hasText(details,"verification"))throw ApiException.badRequest("CASE_INCOMPLETE","发布前须补充原因、实际修复方式和验证步骤");jdbc.update("UPDATE knowledge_cases SET status='PUBLISHED',published_at=?,updated_at=? WHERE id=?",LocalDateTime.now(),LocalDateTime.now(),id);return knowledge(id);}
    public List<Map<String,Object>> knowledge(String projectId,String query){access.require(projectId,"VIEWER");String q=query==null?"":query.strip();return jdbc.query("SELECT * FROM knowledge_cases WHERE project_id=? AND (?='' OR title LIKE CONCAT('%',?,'%') OR category LIKE CONCAT('%',?,'%') OR CAST(tags AS CHAR) LIKE CONCAT('%',?,'%') OR CAST(content AS CHAR) LIKE CONCAT('%',?,'%')) ORDER BY updated_at DESC",(rs,n)->knowledgeRow(rs),projectId,q,q,q,q,q);}

    @Transactional
    public Map<String,Object> createRegressionPlan(String projectId,String targetId,String suiteVersionId,String cron,boolean active,boolean upload,boolean load){
        access.require(projectId,"ADMIN");
        Integer targetCount=jdbc.queryForObject("SELECT COUNT(*) FROM online_targets WHERE id=? AND project_id=? AND (allow_active_scan=TRUE OR ?=FALSE) AND (allow_write_tests=TRUE OR ?=FALSE)",Integer.class,targetId,projectId,active,upload);
        Integer suiteCount=jdbc.queryForObject("SELECT COUNT(*) FROM test_suite_versions v JOIN test_suites s ON s.id=v.suite_id WHERE v.id=? AND s.project_id=?",Integer.class,suiteVersionId,projectId);
        if(targetCount==null||targetCount==0||suiteCount==null||suiteCount==0)throw ApiException.badRequest("REGRESSION_SCOPE_MISMATCH","回归计划的目标、授权或套件版本不属于当前项目");
        String expression=cron==null||cron.isBlank()?"0 0 2 * * *":cron;LocalDateTime next=RegressionSchedule.next(expression);String id=Ulids.next();LocalDateTime now=LocalDateTime.now();jdbc.update("INSERT INTO regression_plans(id,project_id,target_id,suite_version_id,enabled,cron_expression,active_scan_enabled,upload_enabled,load_enabled,next_run_at,created_at,updated_at,created_by) VALUES (?,?,?,?,TRUE,?,?,?,?,?,?,?,?)",id,projectId,targetId,suiteVersionId,expression,active,upload,load,next,now,now,access.currentUser().id());return regression(id);
    }
    public List<Map<String,Object>> regressionPlans(String projectId){access.require(projectId,"VIEWER");return jdbc.query("SELECT * FROM regression_plans WHERE project_id=? ORDER BY updated_at DESC",(rs,n)->regressionRow(rs),projectId);}
    @Transactional
    public Map<String,Object> updateRegressionPlan(String projectId,String planId,Boolean enabled,String cron,Boolean active,Boolean upload,Boolean load){
        access.require(projectId,"ADMIN");Map<String,Object>previous=regression(planId);
        if(!projectId.equals(previous.get("projectId")))throw ApiException.notFound("回归计划不存在");
        boolean newEnabled=enabled==null?Boolean.TRUE.equals(previous.get("enabled")):enabled;
        boolean newActive=active==null?Boolean.TRUE.equals(previous.get("activeScanEnabled")):active;
        boolean newUpload=upload==null?Boolean.TRUE.equals(previous.get("uploadEnabled")):upload;
        boolean newLoad=load==null?Boolean.TRUE.equals(previous.get("loadEnabled")):load;
        String expression=cron==null?String.valueOf(previous.get("cronExpression")):cron.strip();
        LocalDateTime next=RegressionSchedule.next(expression);
        Integer allowed=jdbc.queryForObject("SELECT COUNT(*) FROM online_targets WHERE id=? AND project_id=? AND (allow_active_scan=TRUE OR ?=FALSE) AND (allow_write_tests=TRUE OR ?=FALSE)",Integer.class,previous.get("targetId"),projectId,newActive,newUpload);
        if(allowed==null||allowed==0)throw ApiException.badRequest("REGRESSION_SCOPE_MISMATCH","目标未授权回归计划启用的检测类型");
        jdbc.update("UPDATE regression_plans SET enabled=?,cron_expression=?,active_scan_enabled=?,upload_enabled=?,load_enabled=?,next_run_at=?,updated_at=?,created_by=? WHERE id=? AND project_id=?",newEnabled,expression,newActive,newUpload,newLoad,newEnabled?next:null,LocalDateTime.now(),access.currentUser().id(),planId,projectId);
        return regression(planId);
    }
    public List<Map<String,Object>> notifications(String projectId){access.require(projectId,"VIEWER");String user=access.currentUser().id();return jdbc.query("SELECT n.id,n.type,n.title,n.payload,n.created_at,r.read_at FROM notifications n LEFT JOIN notification_reads r ON r.notification_id=n.id AND r.user_id=? WHERE n.project_id=? AND (n.user_id IS NULL OR n.user_id=?) ORDER BY n.created_at DESC LIMIT 100",(rs,n)->notificationRow(rs),user,projectId,user);}
    @Transactional public void readNotification(String projectId,String id){access.require(projectId,"VIEWER");String user=access.currentUser().id();jdbc.update("INSERT INTO notification_reads(notification_id,user_id,read_at) SELECT id,?,? FROM notifications WHERE id=? AND project_id=? AND (user_id IS NULL OR user_id=?) ON DUPLICATE KEY UPDATE read_at=VALUES(read_at)",user,LocalDateTime.now(),id,projectId,user);}

    private void validateRetest(Map<String,Object>issue,String retestRunId){
        if(retestRunId==null||retestRunId.isBlank())throw ApiException.badRequest("RETEST_REQUIRED","已验证状态必须关联复测运行");
        List<Map<String,Object>>runs=jdbc.queryForList("SELECT id,project_id,target_id,suite_version_id,status,created_at,config_snapshot FROM test_runs WHERE id=?",retestRunId);
        List<Map<String,Object>>original=jdbc.queryForList("SELECT target_id,suite_version_id,created_at,config_snapshot FROM test_runs WHERE id=?",issue.get("runId"));
        boolean hosted=!runs.isEmpty()&&!original.isEmpty()&&com.shijie.accessibility.hosting.HostedRunIdentity.isRetest(snapshot(original.getFirst().get("config_snapshot")),snapshot(runs.getFirst().get("config_snapshot")));
        if(runs.isEmpty()||original.isEmpty()||!Objects.equals(issue.get("projectId"),runs.getFirst().get("project_id"))
                ||!hosted&&!Objects.equals(original.getFirst().get("target_id"),runs.getFirst().get("target_id"))
                ||!Objects.equals(original.getFirst().get("suite_version_id"),runs.getFirst().get("suite_version_id"))
                ||!asLocalDateTime(runs.getFirst().get("created_at")).isAfter(asLocalDateTime(original.getFirst().get("created_at"))))
            throw ApiException.badRequest("INVALID_RETEST","复测必须是同一目标、同一套件版本的后续运行");
        if(!"COMPLETED".equals(String.valueOf(runs.getFirst().get("status"))))throw ApiException.badRequest("RETEST_NOT_COMPLETE","复测运行必须完整完成");
        String module=jdbc.queryForObject("SELECT module_type FROM test_run_modules WHERE id=?",String.class,issue.get("moduleId"));
        Integer checked=jdbc.queryForObject("SELECT COUNT(*) FROM test_run_modules WHERE run_id=? AND module_type=? AND status='COMPLETED'",Integer.class,retestRunId,module);
        if(checked==null||checked==0)throw ApiException.badRequest("RETEST_MODULE_MISSING","复测未完整执行原问题所属模块");
        Integer uncertain=jdbc.queryForObject("SELECT COUNT(*) FROM run_issues i JOIN test_run_modules m ON m.id=i.module_id WHERE i.run_id=? AND m.module_type=? AND i.evidence_confidence='INCONCLUSIVE'",Integer.class,retestRunId,module);
        if(uncertain!=null&&uncertain>0)throw ApiException.badRequest("RETEST_INCONCLUSIVE","复测模块含无法判断的检查，不得据此标记修复完成");
        String issueKey=String.valueOf(issue.get("issueKey"));Integer repeated=jdbc.queryForObject("SELECT COUNT(*) FROM run_issues WHERE run_id=? AND issue_key=?",Integer.class,retestRunId,issueKey);
        if(repeated!=null&&repeated>0)throw ApiException.conflict("RETEST_STILL_FAILING","复测中仍检出同一问题");
    }
    private boolean hasText(Map<?,?> content,String key){Object value=content.get(key);return value instanceof String text&&!text.isBlank();}
    private LocalDateTime asLocalDateTime(Object value){return value instanceof LocalDateTime date?date:((java.sql.Timestamp)value).toLocalDateTime();}
    private Map<String,Object>rawIssue(String id){List<Map<String,Object>>rows=jdbc.query("SELECT i.*,u.display_name assignee_name FROM run_issues i LEFT JOIN app_users u ON u.id=i.assignee_id WHERE i.id=?",(rs,n)->row(rs),id);if(rows.isEmpty())throw ApiException.notFound("修复任务不存在");return rows.getFirst();}
    private List<Map<String,Object>>comments(String id){return jdbc.query("SELECT c.*,u.display_name FROM issue_comments c JOIN app_users u ON u.id=c.user_id WHERE c.issue_id=? ORDER BY c.created_at",(rs,n)->Map.of("id",rs.getString("id"),"body",rs.getString("body"),"displayName",rs.getString("display_name"),"createdAt",rs.getTimestamp("created_at").toLocalDateTime()),id);}
    private List<Map<String,Object>>history(String id){return jdbc.query("SELECT h.*,u.display_name operator_name FROM issue_history h LEFT JOIN app_users u ON u.id=h.user_id WHERE h.issue_id=? ORDER BY h.created_at",(rs,n)->{Map<String,Object>m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("action",rs.getString("action"));m.put("fromStatus",rs.getString("from_status"));m.put("toStatus",rs.getString("to_status"));m.put("details",read(rs.getString("details")));m.put("operatorId",rs.getString("user_id"));m.put("operatorName",rs.getString("operator_name"));m.put("createdAt",rs.getTimestamp("created_at").toLocalDateTime());return m;},id);}
    private void history(String issueId,String action,String from,String to,Object details){jdbc.update("INSERT INTO issue_history(id,issue_id,user_id,action,from_status,to_status,details,created_at) VALUES (?,?,?,?,?,?,?,?)",Ulids.next(),issueId,access.currentUser().id(),action,from,to,write(details),LocalDateTime.now());}
    private Map<String,Object>knowledge(String id){List<Map<String,Object>>rows=jdbc.query("SELECT * FROM knowledge_cases WHERE id=?",(rs,n)->knowledgeRow(rs),id);if(rows.isEmpty())throw ApiException.notFound("知识案例不存在");access.require(String.valueOf(rows.getFirst().get("projectId")),"VIEWER");return rows.getFirst();}
    private Map<String,Object>regression(String id){List<Map<String,Object>>rows=jdbc.query("SELECT * FROM regression_plans WHERE id=?",(rs,n)->regressionRow(rs),id);if(rows.isEmpty())throw ApiException.notFound("回归计划不存在");return rows.getFirst();}
    private Map<String,Object>row(java.sql.ResultSet rs)throws java.sql.SQLException{Map<String,Object>m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("projectId",rs.getString("project_id"));m.put("runId",rs.getString("run_id"));m.put("moduleId",rs.getString("module_id"));m.put("issueKey",rs.getString("issue_key"));m.put("source",rs.getString("source"));m.put("severity",rs.getString("severity"));m.put("evidenceConfidence",rs.getString("evidence_confidence"));m.put("reviewStatus",rs.getString("review_status"));m.put("taskStatus",rs.getString("task_status"));m.put("title",rs.getString("title"));m.put("description",rs.getString("description"));m.put("evidence",read(rs.getString("evidence")));m.put("assigneeId",rs.getString("assignee_id"));m.put("assigneeName",rs.getString("assignee_name"));m.put("retestRunId",rs.getString("retest_run_id"));m.put("recurrenceOfIssueId",rs.getString("recurrence_of_issue_id"));m.put("createdAt",rs.getTimestamp("created_at").toLocalDateTime());m.put("updatedAt",rs.getTimestamp("updated_at").toLocalDateTime());return m;}
    private Map<String,Object>knowledgeRow(java.sql.ResultSet rs)throws java.sql.SQLException{Map<String,Object>m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("projectId",rs.getString("project_id"));m.put("issueId",rs.getString("issue_id"));m.put("status",rs.getString("status"));m.put("title",rs.getString("title"));m.put("category",rs.getString("category"));m.put("tags",read(rs.getString("tags")));m.put("content",read(rs.getString("content")));m.put("publishedAt",rs.getTimestamp("published_at")==null?null:rs.getTimestamp("published_at").toLocalDateTime());m.put("updatedAt",rs.getTimestamp("updated_at").toLocalDateTime());return m;}
    private Map<String,Object>regressionRow(java.sql.ResultSet rs)throws java.sql.SQLException{Map<String,Object>m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("projectId",rs.getString("project_id"));m.put("targetId",rs.getString("target_id"));m.put("suiteVersionId",rs.getString("suite_version_id"));m.put("enabled",rs.getBoolean("enabled"));m.put("cronExpression",rs.getString("cron_expression"));m.put("activeScanEnabled",rs.getBoolean("active_scan_enabled"));m.put("uploadEnabled",rs.getBoolean("upload_enabled"));m.put("loadEnabled",rs.getBoolean("load_enabled"));m.put("nextRunAt",rs.getTimestamp("next_run_at")==null?null:rs.getTimestamp("next_run_at").toLocalDateTime());return m;}
    private Map<String,Object>notificationRow(java.sql.ResultSet rs)throws java.sql.SQLException{Map<String,Object>m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("type",rs.getString("type"));m.put("title",rs.getString("title"));m.put("payload",read(rs.getString("payload")));m.put("readAt",rs.getTimestamp("read_at")==null?null:rs.getTimestamp("read_at").toLocalDateTime());m.put("createdAt",rs.getTimestamp("created_at").toLocalDateTime());return m;}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private Map<?,?> snapshot(Object value){try{return value==null?Map.of():json.readValue(value.toString(),Map.class);}catch(Exception e){return Map.of();}}
    private Object read(String value){try{return value==null?null:json.readValue(value,Object.class);}catch(Exception ex){return value;}}
    private String blankToNull(String value){return value==null||value.isBlank()?null:value;}
}
