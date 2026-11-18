package com.shijie.accessibility.service;

import com.shijie.accessibility.common.*;
import com.shijie.accessibility.hosting.*;
import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Fixed business operations. Neither model output nor browser state is an executable tool. */
@Service
public class GuideWorkflowAdapter {
    private final HostingService hosting;
    private final AgentTaskService agents;
    private final IssueRepairService repairs;
    private final RepairWorkflowService workflow;
    private final TestRunService runs;
    private final TestRunReportService reports;
    private final ProjectService projects;
    private final ProjectAccessService access;
    private final OnlineTargetService targets;
    private final TestSuiteService suites;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public GuideWorkflowAdapter(HostingService hosting, AgentTaskService agents, IssueRepairService repairs,
            RepairWorkflowService workflow, TestRunService runs, TestRunReportService reports,
            ProjectService projects, ProjectAccessService access, OnlineTargetService targets,
            TestSuiteService suites, JdbcTemplate jdbc, ObjectMapper json) {
        this.hosting=hosting; this.agents=agents; this.repairs=repairs; this.workflow=workflow;
        this.runs=runs; this.reports=reports; this.projects=projects; this.access=access;
        this.targets=targets; this.suites=suites; this.jdbc=jdbc; this.json=json;
    }

    public Map<String,Object> validateContext(Map<String,Object> raw) {
        Map<String,Object> c=new LinkedHashMap<>();
        for(String field:List.of("projectId","siteId","versionId","deploymentId","targetId","suiteVersionId",
                "baselineTaskId","taskId","runId","issueId","repairId","snapshotId")) {
            Object value=raw.get(field);
            if(value!=null&&!value.toString().isBlank()) {
                if(!(value instanceof String s)||!s.matches("[0-9A-Z]{26}"))
                    throw ApiException.badRequest("GUIDE_CONTEXT_INVALID","资源编号无效");
                c.put(field,value);
            }
        }
        if(c.containsKey("siteId")) bindProject(c, hosting.get(text(c,"siteId")).projectId());
        if(c.containsKey("deploymentId")) {
            var d=hosting.deployment(text(c,"deploymentId"));
            bindProject(c,hosting.get(d.siteId()).projectId()); bind(c,"siteId",d.siteId());
        }
        if(c.containsKey("versionId")) bindProject(c,projects.version(text(c,"versionId")).projectId);
        if(c.containsKey("targetId")) bindProject(c,targets.get(required(c,"projectId"),text(c,"targetId")).projectId());
        if(c.containsKey("suiteVersionId")) bindProject(c,suites.version(text(c,"suiteVersionId")).projectId());
        if(c.containsKey("taskId")) {
            var task=agents.get(text(c,"taskId")); bindProject(c,task.projectId());
            bind(c,"targetId",task.targetId()); bind(c,"suiteVersionId",task.suiteVersionId());
        }
        if(c.containsKey("runId")) {
            var run=runs.get(text(c,"runId")); bindProject(c,run.projectId());
            // A hosted repair retest uses a new target, so the original issue's run remains separate.
        }
        if(c.containsKey("issueId")) {
            var workspace=repairs.workspace(text(c,"issueId"));
            bindProject(c,String.valueOf(workspace.issue().get("projectId")));
            if(workspace.hostedSiteId()!=null)bind(c,"siteId",workspace.hostedSiteId());
        }
        if(c.containsKey("repairId")) {
            var repair=repairs.get(text(c,"repairId")); bind(c,"issueId",repair.issueId());
            bindProject(c,String.valueOf(repairs.workspace(repair.issueId()).issue().get("projectId")));
        }
        if(c.containsKey("snapshotId")) {
            var snapshot=reports.get(text(c,"snapshotId")); bind(c,"runId",snapshot.runId());
            bindProject(c,runs.get(snapshot.runId()).projectId());
        }
        if(c.containsKey("baselineTaskId")) {
            var base=agents.get(text(c,"baselineTaskId")); bindProject(c,base.projectId());
            if(!Objects.equals(c.get("targetId"),base.targetId())||!Objects.equals(c.get("suiteVersionId"),base.suiteVersionId()))
                throw ApiException.badRequest("GUIDE_BASELINE_MISMATCH","基线必须与目标和方案版本一致");
        }
        if(c.containsKey("projectId")) access.require(text(c,"projectId"),"VIEWER");
        if(c.containsKey("issueId")&&c.containsKey("siteId")) {
            String site=repairs.workspace(text(c,"issueId")).hostedSiteId();
            if(site!=null&&!site.equals(c.get("siteId"))) mismatch();
        }
        return c;
    }

    public Prepared prepare(String type, Map<String,Object> input, Map<String,Object> context, boolean aiConsent) {
        Map<String,Object> a=new LinkedHashMap<>();
        Map<String,Object> preview=new LinkedHashMap<>(); preview.put("operation",type);
        switch(type) {
            case "CREATE_SITE" -> {
                String name=text(input,"name"),purpose=text(input,"purpose");
                if(name.isBlank()||name.length()>120||!Set.of("TEST","HOST").contains(purpose))
                    throw ApiException.badRequest("GUIDE_SITE_INVALID","请填写网站名称并选择用途");
                a.put("name",name);a.put("purpose",purpose);preview.putAll(a);
            }
            case "CONFIGURE_DEPLOY" -> {
                String siteId=required(context,"siteId");var site=hosting.get(siteId);access.require(site.projectId(),"MEMBER");
                if(site.sourceVersionId()==null)throw ApiException.badRequest("SOURCE_REQUIRED","请先上传文件或导入仓库");
                var config=input.get("config")==null?site.config():json.convertValue(input.get("config"),RuntimeConfig.class);
                if(config==null)throw ApiException.badRequest("SOURCE_REQUIRED","请先确认运行设置");
                var policy=input.get("testPolicy")==null?site.testPolicy():json.convertValue(input.get("testPolicy"),HostingService.TestPolicy.class);
                if(policy.write()||policy.active())throw ApiException.badRequest("GUIDE_ADVANCED_TEST","写入和主动扫描请在网站工作区明确登记和确认，向导使用只读检查");
                a.put("siteId",siteId);a.put("versionId",site.sourceVersionId());a.put("config",config);
                a.put("testPolicy",policy);a.put("suiteVersionId",input.getOrDefault("suiteVersionId",site.suiteVersionId()));
                var suite=suites.version(text(a,"suiteVersionId"));if(!suite.projectId().equals(site.projectId()))mismatch();
                preview.putAll(a);preview.put("siteName",site.name());preview.put("notice","启动独立测试环境并自动检测，正式网站更新另行确认。" );
            }
            case "START_AGENT_TASK" -> {
                String project=required(context,"projectId");access.require(project,"MEMBER");
                var task=agents.createDraft(project,required(context,"targetId"),required(context,"suiteVersionId"),
                        Objects.toString(input.get("goal"),"完成日常只读网站体检，解释证据与下一步"),false,"RULE",textOrNull(context,"baselineTaskId"));
                a.put("taskId",task.id());a.put("planHash",task.planHash());preview.put("plan",task.plan());preview.put("goal",task.goal());
                preview.put("taskId",task.id());preview.put("projectId",project);
            }
            case "PREPARE_REPAIR" -> {
                String issue=required(context,"issueId"),version=required(context,"versionId"),path=required(input,"path");
                var workspace=repairs.workspace(issue);access.require(String.valueOf(workspace.issue().get("projectId")),"MEMBER");
                var source=repairs.file(issue,version,path);
                a.put("issueId",issue);a.put("versionId",version);a.put("path",path);a.put("beforeSha",source.sha());
                String mode=Objects.toString(input.get("mode"),"TEMPLATE");a.put("mode",mode);
                if("AI".equals(mode)) {
                    if(!Boolean.TRUE.equals(input.get("repairAiConsent")))
                        throw ApiException.badRequest("AI_CONSENT_REQUIRED","请单独同意发送选中文件与脱敏问题用于 AI 修复");
                    var candidate=repairs.candidate(issue,new IssueRepairService.CandidateRequest(version,path,source.sha(),true));
                    a.put("content",candidate.content());a.put("summary",candidate.summary());a.put("reason",candidate.reason());
                    a.put("benefit",candidate.benefit());a.put("prevention",candidate.prevention());
                } else if("TEMPLATE".equals(mode)) {
                    if(source.templateRule()==null)throw ApiException.badRequest("GUIDE_MANUAL_REPAIR","这个文件没有可用规则补丁，请进入源码编辑器完成修改");
                    a.put("language",Objects.toString(input.get("language"),"zh-CN"));
                    a.put("candidateText",Objects.toString(input.get("candidateText"),""));a.put("decorative",Boolean.TRUE.equals(input.get("decorative")));
                } else throw ApiException.badRequest("GUIDE_REPAIR_MODE","请选择规则补丁或 AI 候选，手动编辑请使用源码编辑器");
                preview.put("issueTitle",workspace.issue().get("title"));preview.put("file",path);preview.put("mode",mode);
                preview.put("notice","准备补丁后将展示完整差异；应用修改需要另行确认。");
            }
            case "APPLY_REPAIR" -> {
                String id=required(context,"repairId");var repair=repairs.get(id);
                access.require(String.valueOf(repairs.workspace(repair.issueId()).issue().get("projectId")),"MEMBER");
                if(!"PENDING".equals(repair.status()))throw ApiException.conflict("REPAIR_NOT_PENDING","补丁已经应用或失效，请刷新流程");
                a.put("repairId",id);preview.put("repairId",id);preview.put("file",repair.targetFile());preview.put("diffText",repair.diffText());
                preview.put("notice","确认差异后创建新源码版本；平台网站自动部署并复测，原正式版本保留。");
            }
            case "PUBLISH_SITE" -> {
                String siteId=required(context,"siteId"),deploymentId=input.containsKey("deploymentId")?required(input,"deploymentId"):required(context,"deploymentId");var site=hosting.get(siteId);
                access.require(site.projectId(),"ADMIN");var d=hosting.deployment(deploymentId);
                if(!siteId.equals(d.siteId()))mismatch();
                if(!"HOST".equals(site.purpose())||!d.testPassed()||!"RUNNING".equals(d.status()))
                    throw ApiException.conflict("RELEASE_NOT_VERIFIED","正式更新需要长期托管用途及完整通过验证的运行版本");
                a.put("siteId",siteId);a.put("deploymentId",deploymentId);a.put("expectedCurrentId",site.currentDeploymentId());
                preview.putAll(a);preview.put("siteName",site.name());preview.put("notice","新版本启动并检查后切换正式入口，数据库继续保留。");
            }
            case "CREATE_REPORT_SNAPSHOT" -> {
                String runId=required(context,"runId");var run=runs.get(runId);access.require(run.projectId(),"MEMBER");
                if(!Set.of("COMPLETED","PARTIAL","FAILED","CANCELLED").contains(run.status()))
                    throw ApiException.conflict("RUN_NOT_FINISHED","运行结束后才能生成报告快照");
                a.put("runId",runId);preview.putAll(a);preview.put("runStatus",run.status());preview.put("issueCount",run.issues().size());
            }
            default -> throw ApiException.badRequest("GUIDE_ACTION_INVALID","向导不支持这个操作");
        }
        rejectSensitive(a);
        return new Prepared(type,a,preview,guard(type,a));
    }

    /** Called under the confirmation transaction, after resource locks and permission checks. */
    public String guard(String type,Map<String,Object> a) {
        Map<String,Object> state=new LinkedHashMap<>();
        switch(type) {
            case "CREATE_SITE" -> state.put("actor",access.currentUser().id());
            case "CONFIGURE_DEPLOY","PUBLISH_SITE" -> {
                var site=hosting.get(required(a,"siteId"));access.require(site.projectId(),"PUBLISH_SITE".equals(type)?"ADMIN":"MEMBER");
                var raw=hosting.rawSite(site.id());
                for(String field:List.of("id","source_version_id","runtime_config","suite_version_id","test_policy","purpose","current_deployment_id","encrypted_environment","source_job_id"))state.put(field,raw.get(field));
                if(site.sourceVersionId()!=null)state.put("manifest",projects.version(site.sourceVersionId()).manifestHash);
                if("PUBLISH_SITE".equals(type)) {
                    var d=hosting.deployment(required(a,"deploymentId"));if(!site.id().equals(d.siteId()))mismatch();
                    state.put("candidate",List.of(d.id(),d.status(),d.testPassed(),d.sourceVersionId(),Objects.toString(d.runId(),"")));
                }
            }
            case "START_AGENT_TASK" -> {
                var task=agents.get(required(a,"taskId"));access.require(task.projectId(),"MEMBER");
                state.put("plan",task.planHash());state.put("status",task.status());
                state.put("targetUpdated",targets.get(task.projectId(),task.targetId()).updatedAt());
            }
            case "PREPARE_REPAIR" -> {
                var w=repairs.workspace(required(a,"issueId"));access.require(String.valueOf(w.issue().get("projectId")),"MEMBER");
                state.put("sha",repairs.file(text(a,"issueId"),required(a,"versionId"),required(a,"path")).sha());
                state.put("manifest",projects.version(text(a,"versionId")).manifestHash);
            }
            case "APPLY_REPAIR" -> {
                var r=repairs.get(required(a,"repairId"));var w=repairs.workspace(r.issueId());access.require(String.valueOf(w.issue().get("projectId")),"MEMBER");
                state.put("status",r.status());state.put("diff",Hashes.sha256(r.diffText()));
                state.put("sha",repairs.file(r.issueId(),r.baseVersionId(),r.targetFile()).sha());
            }
            case "CREATE_REPORT_SNAPSHOT" -> {
                var run=runs.get(required(a,"runId"));access.require(run.projectId(),"MEMBER");state.put("run",run.id());state.put("status",run.status());
            }
            default -> throw ApiException.badRequest("GUIDE_ACTION_INVALID","无效操作");
        }
        return Hashes.sha256(json.writeValueAsString(state));
    }

    public Map<String,Object> execute(String type,Map<String,Object> a,String key,boolean reviewAccepted) throws Exception {
        Map<String,Object> receipt=new LinkedHashMap<>();
        switch(type) {
            case "CREATE_SITE" -> {var s=hosting.create(text(a,"name"),text(a,"purpose"),key);receipt.put("siteId",s.id());receipt.put("projectId",s.projectId());}
            case "CONFIGURE_DEPLOY" -> {
                hosting.configure(text(a,"siteId"),new HostingService.Configure(json.convertValue(a.get("config"),RuntimeConfig.class),null,text(a,"suiteVersionId"),json.convertValue(a.get("testPolicy"),HostingService.TestPolicy.class)));
                var d=hosting.deploy(text(a,"siteId"),new HostingService.DeployRequest(text(a,"versionId"),null,text(a,"suiteVersionId")),key);
                receipt.put("siteId",d.siteId());receipt.put("deploymentId",d.id());receipt.put("versionId",d.sourceVersionId());
            }
            case "START_AGENT_TASK" -> {var t=agents.start(text(a,"taskId"),text(a,"planHash"),key);receipt.put("taskId",t.id());receipt.put("projectId",t.projectId());}
            case "PREPARE_REPAIR" -> {
                var r=repairs.generate(text(a,"issueId"),new IssueRepairService.GenerateRequest(text(a,"versionId"),text(a,"path"),text(a,"beforeSha"),text(a,"mode"),textOrNull(a,"content"),text(a,"language"),text(a,"candidateText"),Boolean.TRUE.equals(a.get("decorative")),textOrNull(a,"summary"),textOrNull(a,"reason"),textOrNull(a,"benefit"),textOrNull(a,"prevention")),key);
                receipt.put("repairId",r.id());receipt.put("issueId",r.issueId());
            }
            case "APPLY_REPAIR" -> {
                if(!reviewAccepted)throw ApiException.badRequest("REPAIR_REVIEW_REQUIRED","请先勾选已审阅差异");
                var r=workflow.apply(text(a,"repairId"),true);receipt.put("repairId",r.id());receipt.put("issueId",r.issueId());receipt.put("versionId",r.newVersionId());
            }
            case "PUBLISH_SITE" -> {var d=hosting.publish(text(a,"siteId"),text(a,"deploymentId"),textOrNull(a,"expectedCurrentId"),key);receipt.put("siteId",d.siteId());receipt.put("deploymentId",d.id());}
            case "CREATE_REPORT_SNAPSHOT" -> {var snapshot=reports.create(text(a,"runId"),key);receipt.put("snapshotId",snapshot.id());receipt.put("runId",snapshot.runId());receipt.put("contentHash",snapshot.contentHash());}
            default -> throw ApiException.badRequest("GUIDE_ACTION_INVALID","无效操作");
        }
        receipt.put("operation",type);return receipt;
    }

    public void cancelPrepared(String type,Map<String,Object> arguments){
        if("START_AGENT_TASK".equals(type))agents.cancel(required(arguments,"taskId"));
    }

    public Map<String,Object> resources(Map<String,Object> c) {
        Map<String,Object> result=new LinkedHashMap<>();
        if(c.containsKey("siteId")) result.put("site",hosting.get(text(c,"siteId")));
        if(c.containsKey("deploymentId")){
            var d=hosting.deployment(text(c,"deploymentId"));result.put("deployment",d);
            if(d.runId()!=null&&!c.containsKey("runId"))result.put("run",runs.get(d.runId()));
        }
        if(c.containsKey("taskId")) {
            var task=agents.get(text(c,"taskId"));result.put("task",task);
            result.put("taskRuns",task.steps().stream().map(AgentTaskService.StepView::runId).filter(Objects::nonNull).distinct().map(runs::get).toList());
            if(task.currentRunId()!=null&&!c.containsKey("runId"))result.put("run",runs.get(task.currentRunId()));
        }
        if(c.containsKey("runId"))result.put("run",runs.get(text(c,"runId")));
        if(c.containsKey("issueId"))result.put("repairWorkspace",repairs.workspace(text(c,"issueId")));
        if(c.containsKey("repairId")) {
            var repair=repairs.get(text(c,"repairId"));result.put("repair",repair);
            var ds=jdbc.queryForList("SELECT id FROM hosted_deployments WHERE repair_id=? ORDER BY created_at DESC LIMIT 1",repair.id());
            if(!ds.isEmpty()) {
                var selected=(HostingService.DeploymentView)result.get("deployment");
                var d=selected!=null&&selected.published()&&Objects.equals(selected.sourceVersionId(),repair.newVersionId())?selected:hosting.deployment((String)ds.getFirst().get("id"));
                result.put("deployment",d);if(d.runId()!=null)result.put("run",runs.get(d.runId()));
            }
            else if(repair.retest()!=null)result.put("run",repair.retest());
        }
        if(c.containsKey("snapshotId")) {
            var snapshot=reports.get(text(c,"snapshotId"));result.put("snapshot",snapshot);
            Map<String,Object> payload=json.readValue(snapshot.payload(),Map.class);
            // Interpret and export the exact same immutable snapshot, never substitute the current run.
            result.put("run",payload.get("run"));
        }
        return result;
    }

    static void bind(Map<String,Object> c,String key,String id){if(c.containsKey(key)&&!Objects.equals(c.get(key),id))mismatch();c.put(key,id);}
    private void bindProject(Map<String,Object> c,String id){access.require(id,"VIEWER");bind(c,"projectId",id);}
    static void mismatch(){throw ApiException.badRequest("GUIDE_RESOURCE_MISMATCH","所选资源不属于同一项目或流程，请重新选择");}
    public static String text(Map<String,Object> map,String key){return Objects.toString(map.get(key),"").strip();}
    public static String textOrNull(Map<String,Object> map,String key){String s=text(map,key);return s.isBlank()?null:s;}
    public static String required(Map<String,Object> map,String key){String s=text(map,key);if(s.isBlank())throw ApiException.badRequest("GUIDE_RESOURCE_REQUIRED","请先选择必要的网站、方案或源码");return s;}
    public static void rejectSensitive(Object value){String raw=new ObjectMapper().writeValueAsString(value);if(!SensitiveText.redact(raw,Integer.MAX_VALUE).equals(raw.strip()))throw ApiException.badRequest("GUIDE_SENSITIVE_INPUT","请通过专用表单填写凭据，向导不会保存或发送密钥");}
    public record Prepared(String type,Map<String,Object> arguments,Map<String,Object> preview,String guardHash){}
}
