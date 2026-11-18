package com.shijie.accessibility.api;

import com.shijie.accessibility.service.ProjectAssistantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProjectAssistantController {
    private final ProjectAssistantService assistant;

    public ProjectAssistantController(ProjectAssistantService assistant) { this.assistant=assistant; }

    @PostMapping("/projects/{projectId}/assistant-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    ProjectAssistantService.SessionView create(@PathVariable String projectId,@RequestBody SessionRequest request) {
        return assistant.create(projectId,request.mode(),Boolean.TRUE.equals(request.aiConsent()));
    }

    @GetMapping("/projects/{projectId}/assistant-sessions")
    List<ProjectAssistantService.SessionView> list(@PathVariable String projectId) { return assistant.list(projectId); }

    @GetMapping("/projects/{projectId}/assistant-recommendation")
    Map<String,Object> recommendation(@PathVariable String projectId) { return assistant.recommendation(projectId); }

    @GetMapping("/assistant-sessions/{sessionId}")
    ProjectAssistantService.SessionView get(@PathVariable String sessionId) { return assistant.get(sessionId); }

    @PostMapping("/assistant-sessions/{sessionId}/messages")
    ProjectAssistantService.MessageResult message(@PathVariable String sessionId,
                                                   @RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody MessageRequest request) {
        return assistant.message(sessionId,new ProjectAssistantService.MessageCommand(request.content(),request.intent(),
                request.targetId(),request.suiteVersionId(),request.baselineTaskId(),request.goal(),request.mode(),
                request.aiConsent(),request.taskId()),key);
    }

    @PostMapping("/assistant-actions/{actionId}/confirm")
    ProjectAssistantService.ActionView confirm(@PathVariable String actionId,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody ConfirmRequest request) {
        return assistant.confirm(actionId,request.actionHash(),key);
    }

    @PostMapping("/assistant-actions/{actionId}/cancel")
    ProjectAssistantService.ActionView cancel(@PathVariable String actionId,
                                               @RequestHeader("Idempotency-Key") String key) {
        return assistant.cancel(actionId,key);
    }

    public record SessionRequest(String mode,Boolean aiConsent) {}
    public record MessageRequest(@NotBlank @Size(max=1000) String content,String intent,String targetId,
                                 String suiteVersionId,String baselineTaskId,@Size(max=1000) String goal,
                                 String mode,Boolean aiConsent,String taskId) {}
    public record ConfirmRequest(@NotBlank @Size(max=64) String actionHash) {}
}
