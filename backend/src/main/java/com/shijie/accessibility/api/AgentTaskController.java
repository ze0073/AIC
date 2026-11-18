package com.shijie.accessibility.api;

import com.shijie.accessibility.service.AgentTaskService;
import com.shijie.accessibility.service.AgentInsightService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AgentTaskController {
    private final AgentTaskService tasks;
    private final AgentInsightService insights;
    public AgentTaskController(AgentTaskService tasks,AgentInsightService insights){this.tasks=tasks;this.insights=insights;}

    @PostMapping("/projects/{projectId}/agent-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    AgentTaskService.TaskView create(@PathVariable String projectId,@Valid @RequestBody DraftRequest request){
        return tasks.createDraft(projectId,request.targetId(),request.suiteVersionId(),request.goal(),Boolean.TRUE.equals(request.aiConsent()),request.mode(),request.baselineTaskId());
    }
    @GetMapping("/projects/{projectId}/agent-tasks/baselines")
    List<Map<String,Object>> baselines(@PathVariable String projectId,@RequestParam String targetId,@RequestParam String suiteVersionId){
        return tasks.baselines(projectId,targetId,suiteVersionId);
    }
    @GetMapping("/projects/{projectId}/agent-tasks")
    List<AgentTaskService.TaskView> list(@PathVariable String projectId){return tasks.list(projectId);}
    @GetMapping("/agent-tasks/{taskId}")
    AgentTaskService.TaskView get(@PathVariable String taskId){return tasks.get(taskId);}
    @GetMapping("/agent-tasks/{taskId}/insights")
    Map<String,Object> insights(@PathVariable String taskId){return insights.insights(taskId);}
    @GetMapping(value="/agent-tasks/{taskId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String taskId){return tasks.subscribe(taskId);}
    @PostMapping("/agent-tasks/{taskId}/start")
    AgentTaskService.TaskView start(@PathVariable String taskId,@RequestHeader("Idempotency-Key")String key,
                                    @Valid @RequestBody StartRequest request){return tasks.start(taskId,request.planHash(),key);}
    @PostMapping("/agent-tasks/{taskId}/cancel")
    AgentTaskService.TaskView cancel(@PathVariable String taskId){return tasks.cancel(taskId);}

    public record DraftRequest(@NotBlank String targetId,@NotBlank String suiteVersionId,@NotBlank @Size(max=1000) String goal,
                               Boolean aiConsent,String mode,String baselineTaskId){}
    public record StartRequest(@NotBlank String planHash){}
}
