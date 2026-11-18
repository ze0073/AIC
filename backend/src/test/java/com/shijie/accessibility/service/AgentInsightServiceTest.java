package com.shijie.accessibility.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentInsightServiceTest {
    @Test void buildsEvidenceLinkedActionsAndDoesNotHideARegressionBehindACompletedStatus(){
        AgentTaskService tasks=mock(AgentTaskService.class);
        TestRunService runs=mock(TestRunService.class);
        RunComparisonService comparisons=mock(RunComparisonService.class);
        LocalDateTime now=LocalDateTime.of(2026,9,30,10,0);
        AgentTaskService.TaskView before=task("before","before-run",now.minusDays(1));
        AgentTaskService.TaskView current=task("current","current-run",now);
        when(tasks.get("current")).thenReturn(current);
        when(tasks.list("project")).thenReturn(List.of(current,before));
        when(runs.get("before-run")).thenReturn(run("before-run",now.minusDays(1),List.of()));
        when(runs.get("current-run")).thenReturn(run("current-run",now,List.of(new TestRunService.IssueView(
                "issue","current-module","BROWSER","HIGH","HIGH","PENDING_REVIEW","OPEN",
                "入口按钮无法识别","按钮没有可访问名称","{}",now))));
        when(comparisons.agentMetrics(any(),eq("FRONTEND"))).thenAnswer(call->{
            TestRunService.RunView value=call.getArgument(0);
            double navigation=value.id().equals("current-run")?180:100;
            return Map.of("pages",Map.of("/",Map.of("navigationMs",navigation,"lcpMs",navigation+20)));
        });
        AgentInsightService service=new AgentInsightService(tasks,runs,comparisons);

        Map<String,Object> insight=service.insights("current");

        assertThat(insight.get("schemaVersion")).isEqualTo("0.8.0");
        Map<?,?> counts=(Map<?,?>)insight.get("counts");
        assertThat(counts.get("high")).isEqualTo(1L);assertThat(counts.get("pendingReview")).isEqualTo(1L);
        List<?> actions=(List<?>)insight.get("actionItems");
        assertThat(actions).singleElement().satisfies(raw->{
            Map<?,?> action=(Map<?,?>)raw;
            assertThat(action.get("priority")).isEqualTo("P0");assertThat(action.get("issueId")).isEqualTo("issue");
            assertThat(action.get("detailPath")).isEqualTo("/projects/project/collaboration?issueId=issue");
            assertThat(action.get("prevention").toString()).contains("提交前检查");
        });
        Map<?,?> trend=(Map<?,?>)insight.get("trend");
        Map<?,?> assessment=(Map<?,?>)trend.get("assessment");
        assertThat(assessment.get("overall")).isEqualTo("WORSE");assertThat(assessment.get("experience")).isEqualTo("WORSE");
    }

    private AgentTaskService.TaskView task(String id,String runId,LocalDateTime finished){
        Map<String,Object> plan=Map.of("modules",List.of("FRONTEND"));
        Map<String,Object> summary=Map.of("completedModules",List.of("FRONTEND"),"incompleteModules",List.of(),"unexecutedModules",List.of());
        AgentTaskService.StepView step=new AgentTaskService.StepView(1,"RUN_MODULE","执行页面检查",Map.of("module","FRONTEND"),
                Map.of("status","COMPLETED"),"COMPLETED",runId,finished.minusMinutes(2),finished);
        return new AgentTaskService.TaskView(id,"project","target","suite","actor","检查页面体验","COMPLETED",plan,"hash",summary,null,false,null,
                finished.minusMinutes(3),finished.minusMinutes(2),finished,List.of(step));
    }

    private TestRunService.RunView run(String id,LocalDateTime finished,List<TestRunService.IssueView> issues){
        TestRunService.ModuleView module=new TestRunService.ModuleView(id.equals("current-run")?"current-module":"before-module","FRONTEND","COMPLETED","页面检查完成","{}",finished.minusMinutes(1),finished);
        return new TestRunService.RunView(id,"project","target","suite","COMPLETED",false,"{}","{}",null,finished.minusMinutes(2),finished.minusMinutes(1),finished,List.of(module),issues);
    }
}
