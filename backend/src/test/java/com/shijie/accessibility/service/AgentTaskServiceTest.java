package com.shijie.accessibility.service;

import com.shijie.accessibility.ai.AgentModelClient;
import com.shijie.accessibility.ai.AiProviderException;
import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.security.AuthService;
import com.shijie.accessibility.security.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskServiceTest {
    @Test void deniedProjectAccessDoesNotSendModelInputOrWriteDraft() {
        Fixture f=new Fixture();
        doThrow(ApiException.forbidden("无权访问该项目")).when(f.access).require("project","MEMBER");
        assertThatThrownBy(()->f.draft(true)).isInstanceOf(ApiException.class);
        verifyNoInteractions(f.model);
        assertThat(f.updates).isEmpty();
    }

    @Test void missingConsentOrUnavailableModelDoesNotCreateOrExecuteAnything() {
        Fixture f=new Fixture();
        assertThatThrownBy(()->f.draft(false)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AGENT_AI_CONSENT_REQUIRED");
        verify(f.model,never()).available();
        when(f.model.available()).thenReturn(false);
        when(f.model.unavailableReason()).thenReturn("未配置模型");
        assertThatThrownBy(()->f.draft(true)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AGENT_MODEL_UNAVAILABLE");
        verify(f.model,never()).propose(anyMap());
        assertThat(f.updates).isEmpty();
    }

    @Test void foreignSuiteIsRejectedBeforeCallingTheModel() {
        Fixture f=new Fixture();
        when(f.suites.version("version")).thenReturn(new TestSuiteService.SuiteVersion("version","suite","foreign",1,Map.of(),f.created));
        assertThatThrownBy(()->f.draft(true)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SUITE_PROJECT_MISMATCH");
        verify(f.model,never()).propose(anyMap());
        assertThat(f.updates).isEmpty();
    }

    @Test void planningRedactsSecretsAndDoesNotAuthorizeRequestedFlowOrEscalateLoad() {
        Fixture f=new Fixture();
        Map<String,Object> performance=Map.of("vus",List.of(1),"stageDurationSeconds",20,"ratePerSecond",2,"p95Ms",1000,"errorRate",.01);
        when(f.suites.version("version")).thenReturn(new TestSuiteService.SuiteVersion("version","suite","project",1,
                Map.of("performance",performance,"flowSteps",List.of(Map.of("action","FILL","selector","input","value","private-flow"))),f.created));
        when(f.model.propose(anyMap())).thenAnswer(call->{
            assertThat(call.getArgument(0).toString()).doesNotContain("private-");
            return new AgentModelClient.Plan(List.of("FRONTEND","PERFORMANCE"),true,"token=private-reason");
        });
        var task=f.service.createDraft("project","target","version","检查页面\nAuthorization: Bearer private-bearer\nCookie: sid=private-cookie",true);
        assertThat(task.goal()).doesNotContain("private-");
        assertThat(task.plan().get("includeFlow")).isEqualTo(false);
        assertThat(task.plan().get("flowSteps")).isEqualTo(List.of());
        assertThat(task.plan().get("activeScan")).isEqualTo(false);
        Map<String,Object> expected=new HashMap<>(performance);expected.put("path","");
        assertThat(task.plan().get("performance")).isEqualTo(expected);
        assertThat(task.plan().toString()).doesNotContain("private-");
        verifyNoInteractions(f.executor,f.runs);
    }

    @Test void invalidPlanModulesAreRejectedByTheService() {
        Fixture f=new Fixture();
        when(f.model.propose(anyMap())).thenReturn(new AgentModelClient.Plan(List.of("DELETE"),false,"越界"));
        assertThatThrownBy(()->f.draft(true)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AGENT_PLAN_INVALID");
        assertThat(f.updates).isEmpty();
    }

    @Test void ruleModeSelectsKeywordsWithoutModelConsentOrRequests() {
        Fixture f=new Fixture();
        var task=f.service.createDraft("project","target","version","只检查页面和性能",false,"RULE",null);
        assertThat(task.plan().get("modules")).isEqualTo(List.of("FRONTEND","PERFORMANCE"));
        assertThat(task.plan().get("mode")).isEqualTo("RULE");
        assertThat(task.plan().get("modelInput")).isEqualTo(Map.of());
        verifyNoInteractions(f.model);
        assertThat(AgentTaskService.ruleModules("检查全部质量")).containsExactly("FRONTEND","SECURITY","PERFORMANCE");
    }

    @Test void qwenDecisionMustReferenceRealCompletedObservation() {
        Fixture f=new Fixture();f.plan.put("modules",List.of("FRONTEND","PERFORMANCE"));
        when(f.model.decide(anyMap())).thenReturn(f.runDecision(),
                new AgentModelClient.Decision("FINISH","","","依据第一次运行结束",List.of(99)));
        f.execute();
        assertThat(f.state).isEqualTo("PARTIAL");
        assertThat(f.steps).extracting(AgentTaskService.StepView::action).contains("REJECTED_ACTION");
        assertThat(f.summary.get("schemaVersion")).isEqualTo("0.7.0");
        assertThat(f.summary.get("unexecutedModules")).isEqualTo(List.of("PERFORMANCE"));
    }

    @Test void ruleExecutionNeverCallsTheModelAndKeepsEvidence() {
        Fixture f=new Fixture();f.plan.put("mode","RULE");
        f.execute();
        assertThat(f.state).isEqualTo("COMPLETED");
        assertThat(f.summary.get("mode")).isEqualTo("RULE");
        assertThat(f.summary.get("runIds")).isEqualTo(List.of("run"));
        assertThat(f.steps.getFirst().argument().get("decisionSource")).isEqualTo("RULE");
        verify(f.model,never()).decide(anyMap());
    }

    @Test void hashExpiryCreatorAndTargetChangesRejectStartBeforeQueueingWork() {
        Fixture f=new Fixture();
        assertThatThrownBy(()->f.service.start(f.id,"wrong","key")).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AGENT_PLAN_CHANGED");
        f.created=LocalDateTime.now().minusMinutes(31);
        assertThatThrownBy(()->f.service.start(f.id,f.hash,"key")).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AGENT_PLAN_EXPIRED");
        f.created=LocalDateTime.now();
        when(f.access.currentUser()).thenReturn(new AuthService.UserView("other","other","其他成员",true,f.created));
        assertThatThrownBy(()->f.service.start(f.id,f.hash,"key")).isInstanceOf(ApiException.class)
                .hasMessageContaining("创建者");
        when(f.access.currentUser()).thenReturn(f.actor());
        when(f.targets.get("project","target")).thenReturn(f.target(f.targetUpdated.plusSeconds(1)));
        assertThatThrownBy(()->f.service.start(f.id,f.hash,"key")).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AGENT_TARGET_CHANGED");
        verifyNoInteractions(f.executor);
        assertThat(f.updates).isEmpty();
    }

    @Test void cancelledWhileWaitingForModelDoesNotStartAnotherRun() {
        Fixture f=new Fixture();
        when(f.model.decide(anyMap())).thenAnswer(call->{f.service.cancel(f.id);return f.runDecision();});
        f.execute();
        assertThat(f.state).isEqualTo("CANCELLED");
        assertThat(f.summary.get("unexecutedModules")).isEqualTo(List.of("FRONTEND"));
        verifyNoInteractions(f.runs);
    }

    @Test void revokedDuringModelRequestPreventsActions() {
        Fixture f=new Fixture();
        when(f.model.decide(anyMap())).thenAnswer(call->{f.allowed=0;return f.runDecision();});
        f.execute();
        assertThat(f.state).isEqualTo("FAILED");
        assertThat(f.summary.get("message").toString()).contains("无执行权限");
        verifyNoInteractions(f.runs);
    }

    @Test void cancellationWinsOverAModelErrorAndDoesNotStartARun() {
        Fixture f=new Fixture();
        when(f.model.decide(anyMap())).thenAnswer(call->{f.service.cancel(f.id);throw new AiProviderException("模型连接中断");});
        f.execute();
        assertThat(f.state).isEqualTo("CANCELLED");
        assertThat(f.steps).isEmpty();
        assertThat(f.summary.get("unexecutedModules")).isEqualTo(List.of("FRONTEND"));
        verifyNoInteractions(f.runs);
    }

    @Test void revokedBeforeDecisionPreventsSendingMoreDataToTheModel() {
        Fixture f=new Fixture();f.allowed=0;
        f.execute();
        assertThat(f.state).isEqualTo("FAILED");
        verify(f.model,never()).decide(anyMap());
        verifyNoInteractions(f.runs);
    }

    @Test void unsupportedActionAndUnapprovedModuleCannotReachRunners() {
        for(var decision:List.of(new AgentModelClient.Decision("DELETE","","","非法动作"),
                new AgentModelClient.Decision("RUN_MODULE","SECURITY","","越范围"))){
            Fixture f=new Fixture();when(f.model.decide(anyMap())).thenReturn(decision);
            f.execute();
            assertThat(f.state).isEqualTo("FAILED");
            assertThat(f.steps).extracting(AgentTaskService.StepView::action).containsExactly("REJECTED_ACTION");
            verifyNoInteractions(f.runs);
        }
    }

    @Test void toolFailureKeepsTheRunLinkAndDoesNotBecomeSuccessful() {
        Fixture f=new Fixture();when(f.runs.agentGet("run")).thenReturn(f.run("FAILED"));
        f.execute();
        assertThat(f.state).isEqualTo("PARTIAL");
        assertThat(f.summary.get("completedModules")).isEqualTo(List.of());
        assertThat(f.summary.get("incompleteModules")).isEqualTo(List.of("FRONTEND"));
        assertThat(f.summary.get("runIds")).isEqualTo(List.of("run"));
        assertThat(f.steps.getFirst().status()).isEqualTo("FAILED");
    }

    @Test void modelFailureAfterACompletedModulePreservesMeasuredRunAndSanitizesError() {
        Fixture f=new Fixture();f.plan.put("modules",List.of("FRONTEND","PERFORMANCE"));
        when(f.model.decide(anyMap())).thenReturn(f.runDecision()).thenThrow(new AiProviderException("模型中断 Authorization: Bearer private-error"));
        f.execute();
        assertThat(f.state).isEqualTo("PARTIAL");
        assertThat(f.summary.get("completedModules")).isEqualTo(List.of("FRONTEND"));
        assertThat(f.summary.get("unexecutedModules")).isEqualTo(List.of("PERFORMANCE"));
        assertThat(f.summary.get("runIds")).isEqualTo(List.of("run"));
        assertThat(f.summary.toString()).doesNotContain("private-error");
        assertThat(f.steps.toString()).doesNotContain("private-error");
    }

    @Test void repeatedStartUsesTheExistingReceiptAndDoesNotQueueAgain() {
        Fixture f=new Fixture();f.start();
        when(f.idempotency.find("key","start-agent-task")).thenReturn(Optional.of(f.id));
        f.service.start(f.id,f.hash,"key");
        verify(f.executor,times(1)).execute(any(Runnable.class));
        when(f.idempotency.find("other-key","start-agent-task")).thenReturn(Optional.of("foreign-task"));
        assertThatThrownBy(()->f.service.start(f.id,f.hash,"other-key")).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static class Fixture {
        final ObjectMapper json=new ObjectMapper();
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);
        final ProjectAccessService access=mock(ProjectAccessService.class);
        final OnlineTargetService targets=mock(OnlineTargetService.class);
        final TestSuiteService suites=mock(TestSuiteService.class);
        final TestRunService runs=mock(TestRunService.class);
        final IdempotencyService idempotency=mock(IdempotencyService.class);
        final AgentModelClient model=mock(AgentModelClient.class);
        final TaskExecutor executor=mock(TaskExecutor.class);
        final AgentTaskService service;
        String id="task",hash="hash",state="AWAITING_START",goal="检查页面",currentRun;
        LocalDateTime created=LocalDateTime.now(),started,targetUpdated=LocalDateTime.of(2026,9,27,0,0);
        int allowed=1;boolean cancelled;
        final Map<String,Object> plan=new HashMap<>(Map.of("modules",List.of("FRONTEND"),"includeFlow",false,"targetUpdatedAt",targetUpdated.toString()));
        Map<String,Object> summary=Map.of();
        final List<AgentTaskService.StepView> steps=new ArrayList<>();
        final List<String> stepIds=new ArrayList<>(),updates=new ArrayList<>();
        Runnable queued;

        Fixture(){
            when(access.currentUser()).thenReturn(actor());
            when(targets.get("project","target")).thenReturn(target(targetUpdated));
            when(suites.version("version")).thenReturn(new TestSuiteService.SuiteVersion("version","suite","project",1,Map.of(),created));
            doAnswer(call->"RUNNING".equals(state)&&started==null?List.of(Map.of("id",id)):List.of()).when(jdbc).queryForList(eq("SELECT id FROM agent_tasks WHERE status='RUNNING' AND started_at IS NULL ORDER BY created_at LIMIT 1"));
            when(model.available()).thenReturn(true);when(model.model()).thenReturn("test-model");
            when(model.propose(anyMap())).thenReturn(new AgentModelClient.Plan(List.of("FRONTEND"),false,"检查页面"));
            when(model.decide(anyMap())).thenReturn(runDecision());
            when(idempotency.find(anyString(),anyString())).thenReturn(Optional.empty());
            doAnswer(call->List.of(task())).when(jdbc).query(eq("SELECT * FROM agent_tasks WHERE id=?"),any(RowMapper.class),anyString());
            doAnswer(call->List.copyOf(steps)).when(jdbc).query(eq("SELECT * FROM agent_steps WHERE task_id=? ORDER BY step_no"),any(RowMapper.class),anyString());
            doAnswer(call->allowed).when(jdbc).queryForObject(startsWith("SELECT COUNT(*) FROM project_members"),eq(Integer.class),eq("project"),eq("actor"));
            doAnswer(call->{String sql=call.getArgument(0);Object[] args=(Object[])call.getRawArguments()[1];updates.add(sql);update(sql,args);return 1;})
                    .when(jdbc).update(anyString(),any(Object[].class));
            doAnswer(call->{queued=call.getArgument(0);return null;}).when(executor).execute(any(Runnable.class));
            when(runs.createAgent(anyString(),anyString(),anyString(),anyString(),anyString(),anyBoolean(),anyString(),anyString())).thenReturn(run("RUNNING"));
            when(runs.agentGet("run")).thenReturn(run("COMPLETED"));
            service=new AgentTaskService(jdbc,json,access,targets,suites,runs,idempotency,model,executor,new AgentTaskEvents());
        }

        AuthService.UserView actor(){return new AuthService.UserView("actor","actor","测试成员",true,created);}
        OnlineTargetService.TargetView target(LocalDateTime updated){return new OnlineTargetService.TargetView("target","project","测试站","http://127.0.0.1/","http://127.0.0.1:80",List.of("127.0.0.1"),List.of("/"),false,false,null,created,updated);}
        AgentModelClient.Decision runDecision(){return new AgentModelClient.Decision("RUN_MODULE","FRONTEND","","执行页面检查");}
        AgentTaskService.TaskView draft(boolean consent){return service.createDraft("project","target","version",goal,consent);}
        AgentTaskService.TaskView task(){return new AgentTaskService.TaskView(id,"project","target","version","actor",goal,state,Map.copyOf(plan),hash,summary,currentRun,cancelled,null,created,started,null,List.copyOf(steps));}
        TestRunService.RunView run(String status){return new TestRunService.RunView("run","project","target","version",status,false,"{}","{}",null,created,started,null,List.of(new TestRunService.ModuleView("module","FRONTEND",status,"实际结果","{}",started,null)),List.of());}
        void start(){
            TransactionSynchronizationManager.initSynchronization();
            try{service.start(id,hash,"key");for(var synchronization:TransactionSynchronizationManager.getSynchronizations())synchronization.afterCommit();}
            finally{TransactionSynchronizationManager.clearSynchronization();}
            service.dispatchPending();
        }
        void execute(){start();queued.run();}
        Map<String,Object> read(Object value){return json.readValue(String.valueOf(value),Map.class);}

        void update(String sql,Object[] args){
            if(sql.startsWith("INSERT INTO agent_tasks")){id=(String)args[0];goal=(String)args[5];plan.clear();plan.putAll(read(args[9]));hash=(String)args[10];}
            else if(sql.contains("SET status='RUNNING',started_at")){state="RUNNING";started=null;}
            else if(sql.startsWith("UPDATE agent_tasks SET started_at="))started=(LocalDateTime)args[0];
            else if(sql.startsWith("UPDATE agent_tasks SET status='CANCELLED'")){state="CANCELLED";cancelled=true;}
            else if(sql.contains("SET cancel_requested=TRUE"))cancelled=true;
            else if(sql.contains("SET status=?,summary_json")){state=(String)args[0];summary=read(args[1]);currentRun=null;}
            else if(sql.startsWith("INSERT INTO agent_steps")){
                stepIds.add((String)args[0]);steps.add(new AgentTaskService.StepView((Integer)args[2],(String)args[3],(String)args[4],read(args[5]),null,"RUNNING",null,(LocalDateTime)args[6],null));
            }else if(sql.startsWith("UPDATE agent_steps SET status='")){
                String stepStatus=sql.substring(sql.indexOf("status='")+8).split("'",2)[0];
                for(int index=0;index<steps.size();index++)if("RUNNING".equals(steps.get(index).status()))replace(index,read(args[0]),stepStatus,null);
            }else if(sql.contains("agent_steps SET run_id"))replace(stepIds.indexOf(args[1]),null,null,(String)args[0]);
            else if(sql.contains("agent_steps SET result_json"))replace(stepIds.indexOf(args[3]),read(args[0]),(String)args[1],null);
            else if(sql.contains("agent_tasks SET current_run_id=NULL"))currentRun=null;
            else if(sql.contains("agent_tasks SET current_run_id=?"))currentRun=(String)args[0];
        }
        void replace(int index,Map<String,Object> result,String status,String runId){
            var old=steps.get(index);steps.set(index,new AgentTaskService.StepView(old.stepNo(),old.action(),old.reason(),old.argument(),result==null?old.result():result,status==null?old.status():status,runId==null?old.runId():runId,old.createdAt(),LocalDateTime.now()));
        }
    }
}
