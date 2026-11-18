package com.shijie.accessibility.api;

import com.shijie.accessibility.service.CollaborationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CollaborationController {
    private final CollaborationService collaboration; private final com.shijie.accessibility.service.IssueExplanationService explanations; public CollaborationController(CollaborationService collaboration,com.shijie.accessibility.service.IssueExplanationService explanations){this.collaboration=collaboration;this.explanations=explanations;}
    @GetMapping("/projects/{projectId}/issues") List<Map<String,Object>>issues(@PathVariable String projectId,@RequestParam(required=false)String status,@RequestParam(required=false)String severity){return collaboration.issues(projectId,status,severity);}
    @GetMapping("/issues/{issueId}") Map<String,Object>issue(@PathVariable String issueId){return collaboration.issue(issueId);}
    @PostMapping("/issues/{issueId}/status") Map<String,Object>update(@PathVariable String issueId,@RequestHeader("Idempotency-Key")String key,@RequestBody IssueUpdate body){return collaboration.updateIssue(issueId,body.status(),body.assigneeId(),body.retestRunId(),key);}
    @PostMapping("/issues/{issueId}/review") Map<String,Object>review(@PathVariable String issueId,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody ReviewRequest body){return collaboration.reviewIssue(issueId,body.decision(),body.reason(),key);}
    @PostMapping("/issues/{issueId}/comments") @ResponseStatus(HttpStatus.CREATED) Map<String,Object>comment(@PathVariable String issueId,@Valid @RequestBody Comment body){return collaboration.comment(issueId,body.body());}
    @PostMapping("/issues/{issueId}/ai-explanation") com.shijie.accessibility.service.IssueExplanationService.Explanation explain(@PathVariable String issueId,@RequestParam(defaultValue="true")boolean useAi){return explanations.explain(issueId,useAi);}
    @GetMapping("/projects/{projectId}/knowledge-cases") List<Map<String,Object>>cases(@PathVariable String projectId,@RequestParam(required=false)String query){return collaboration.knowledge(projectId,query);}
    @PostMapping("/projects/{projectId}/knowledge-cases") @ResponseStatus(HttpStatus.CREATED) Map<String,Object>createCase(@PathVariable String projectId,@Valid @RequestBody CaseRequest body){return collaboration.createCase(projectId,body.issueId(),body.title(),body.category(),body.tags(),body.content());}
    @PostMapping("/knowledge-cases/{caseId}/publish") Map<String,Object>publish(@PathVariable String caseId){return collaboration.publishCase(caseId);}
    @GetMapping("/projects/{projectId}/regression-plans") List<Map<String,Object>>plans(@PathVariable String projectId){return collaboration.regressionPlans(projectId);}
    @PostMapping("/projects/{projectId}/regression-plans") @ResponseStatus(HttpStatus.CREATED) Map<String,Object>createPlan(@PathVariable String projectId,@Valid @RequestBody PlanRequest body){return collaboration.createRegressionPlan(projectId,body.targetId(),body.suiteVersionId(),body.cronExpression(),Boolean.TRUE.equals(body.activeScanEnabled()),Boolean.TRUE.equals(body.uploadEnabled()),Boolean.TRUE.equals(body.loadEnabled()));}
    @PatchMapping("/projects/{projectId}/regression-plans/{planId}") Map<String,Object>updatePlan(@PathVariable String projectId,@PathVariable String planId,@RequestBody PlanUpdate body){return collaboration.updateRegressionPlan(projectId,planId,body.enabled(),body.cronExpression(),body.activeScanEnabled(),body.uploadEnabled(),body.loadEnabled());}
    @GetMapping("/projects/{projectId}/notifications") List<Map<String,Object>>notifications(@PathVariable String projectId){return collaboration.notifications(projectId);}
    @PostMapping("/projects/{projectId}/notifications/{notificationId}/read") @ResponseStatus(HttpStatus.NO_CONTENT) void read(@PathVariable String projectId,@PathVariable String notificationId){collaboration.readNotification(projectId,notificationId);}
    public record IssueUpdate(String status,String assigneeId,String retestRunId){}
    public record ReviewRequest(@NotBlank String decision,@NotBlank @Size(max=2000)String reason){}
    public record Comment(@NotBlank @Size(max=3000)String body){}
    public record CaseRequest(String issueId,@NotBlank @Size(max=300)String title,@Size(max=80)String category,@Size(max=20)List<String>tags,@NotNull Map<String,Object>content){}
    public record PlanRequest(@NotBlank String targetId,@NotBlank String suiteVersionId,String cronExpression,Boolean activeScanEnabled,Boolean uploadEnabled,Boolean loadEnabled){}
    public record PlanUpdate(Boolean enabled,String cronExpression,Boolean activeScanEnabled,Boolean uploadEnabled,Boolean loadEnabled){}
}
