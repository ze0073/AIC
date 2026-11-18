package com.shijie.accessibility.service;

import com.shijie.accessibility.hosting.*;
import com.shijie.accessibility.security.ProjectAccessService;
import com.shijie.accessibility.online.OnlineTargetService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuideWorkflowAdapterTest {
    HostingService hosting=mock(HostingService.class);AgentTaskService agents=mock(AgentTaskService.class);
    IssueRepairService repairs=mock(IssueRepairService.class);RepairWorkflowService workflow=mock(RepairWorkflowService.class);
    ProjectAccessService access=mock(ProjectAccessService.class);ProjectService projects=mock(ProjectService.class);
    TestRunReportService reports=mock(TestRunReportService.class);TestRunService runs=mock(TestRunService.class);
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    GuideWorkflowAdapter adapter=new GuideWorkflowAdapter(hosting,agents,repairs,workflow,runs,reports,projects,access,mock(OnlineTargetService.class),mock(TestSuiteService.class),jdbc,new ObjectMapper());
    String site="01K00000000000000000000001",project="01K00000000000000000000002",deployment="01K00000000000000000000003";
    HostingService.SiteView website(String projectId){return new HostingService.SiteView(site,projectId,"网站","HOST","ACTIVE",null,true,null,null,null,null,null,null,null,null,null,false,false,new HostingService.TestPolicy(false,false,false),"/",null,List.of());}
    @Test void rejectsMixedProjectResourcesBeforeExecution(){
        when(hosting.get(site)).thenReturn(website(project));
        assertThatThrownBy(()->adapter.validateContext(Map.of("siteId",site,"projectId","01K00000000000000000000004"))).hasMessageContaining("同一项目");
        verifyNoInteractions(agents,workflow);
    }
    @Test void checksProjectPermissionWhenBindingSite(){
        when(hosting.get(site)).thenReturn(website(project));
        var context=adapter.validateContext(Map.of("siteId",site));
        assertThat(context).containsEntry("projectId",project);verify(access,atLeastOnce()).require(project,"VIEWER");
    }
    @Test void refusesUnverifiedPublishedCandidate(){
        when(hosting.get(site)).thenReturn(website(project));
        when(hosting.deployment(deployment)).thenReturn(new HostingService.DeploymentView(deployment,site,"version","RUNNING",null,null,"TEST",null,null,null,"run","suite","COMPLETED",1,false,false,"/",null,null,null,List.of()));
        assertThatThrownBy(()->adapter.prepare("PUBLISH_SITE",Map.of(),Map.of("siteId",site,"deploymentId",deployment),false)).hasMessageContaining("完整通过验证");
        verify(hosting,never()).publish(any(),any(),any(),any());
    }
    @Test void publishingFromRepairFreezesTheSelectedRetestDeployment(){
        when(hosting.get(site)).thenReturn(website(project));
        when(hosting.rawSite(site)).thenReturn(Map.of("id",site));
        when(hosting.deployment(deployment)).thenReturn(new HostingService.DeploymentView(deployment,site,"new","RUNNING",null,"repair","TEST",null,null,null,"run","suite","COMPLETED",0,true,false,"/",null,null,null,List.of()));
        var prepared=adapter.prepare("PUBLISH_SITE",Map.of("deploymentId",deployment),Map.of("siteId",site,"repairId","repair"),false);
        assertThat(prepared.arguments()).containsEntry("deploymentId",deployment);
        assertThat(prepared.preview()).containsEntry("deploymentId",deployment);
        verify(hosting,never()).publish(any(),any(),any(),any());
    }
    @Test void publishedRepairReceiptIsNotReplacedByItsTestEnvironment(){
        var formal=new HostingService.DeploymentView(deployment,site,"new","RUNNING",null,"repair","HOST",null,null,null,null,"suite","COMPLETED",0,true,true,"/",null,null,null,List.of());
        when(hosting.deployment(deployment)).thenReturn(formal);
        when(repairs.get("repair")).thenReturn(new IssueRepairService.RepairDetail("repair","issue","base","new","index.html","VERIFIED","TEMPLATE","diff","content",Map.of(),null,null,null,null));
        when(jdbc.queryForList(anyString(),eq("repair"))).thenReturn(List.of(Map.of("id","old-test-deployment")));
        assertThat(adapter.resources(Map.of("deploymentId",deployment,"repairId","repair")).get("deployment")).isSameAs(formal);
        verify(hosting,never()).deployment("old-test-deployment");
    }
    @Test void applyingRepairUsesTheSharedOrchestrator() throws Exception {
        var detail=new IssueRepairService.RepairDetail("repair","issue","base","new","index.html","APPLIED","TEMPLATE","diff","content",Map.of(),null,null,null,null);
        when(workflow.apply("repair",true)).thenReturn(detail);
        assertThat(adapter.execute("APPLY_REPAIR",Map.of("repairId","repair"),"stable-key",true)).containsEntry("versionId","new");
        verify(workflow).apply("repair",true);verify(repairs,never()).apply(anyString(),anyBoolean());
        verify(hosting,never()).deploy(anyString(),any(),anyString());
    }
    @Test void repairDiffRequiresReviewBeforeExecution() {
        assertThatThrownBy(()->adapter.execute("APPLY_REPAIR",Map.of("repairId","repair"),"key",false)).hasMessageContaining("审阅");
        verifyNoInteractions(workflow);
    }
    @Test void unknownModelOperationCannotExecute(){
        assertThatThrownBy(()->adapter.prepare("DELETE_SITE",Map.of(),Map.of(),false)).hasMessageContaining("不支持");
        verifyNoInteractions(hosting);
    }
    @Test void credentialsCannotBecomeStoredGuideParameters(){
        assertThatThrownBy(()->GuideWorkflowAdapter.rejectSensitive(Map.of("goal","Token=private-canary"))).hasMessageContaining("专用表单");
    }
}
