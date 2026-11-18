package com.shijie.accessibility.api;

import com.shijie.accessibility.service.TestRunService;
import com.shijie.accessibility.service.RunComparisonService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TestRunController {
    private final TestRunService runs;
    private final RunComparisonService comparisons;
    public TestRunController(TestRunService runs,RunComparisonService comparisons){this.runs=runs;this.comparisons=comparisons;}

    @PostMapping("/test-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    TestRunService.RunView create(@Valid @RequestBody RunRequest body,@RequestHeader("Idempotency-Key")String key){
        return runs.create(new TestRunService.CreateRun(body.projectId(),body.targetId(),body.suiteVersionId(),body.modules(),Boolean.TRUE.equals(body.activeScan())),key);
    }
    @GetMapping("/test-runs/{runId}") TestRunService.RunView get(@PathVariable String runId){return runs.get(runId);}
    @GetMapping("/test-runs/{runId}/comparison") Map<String,Object> compare(@PathVariable String runId,@RequestParam String baselineRunId){return comparisons.compare(runId,baselineRunId);}
    @GetMapping("/test-runs/recent") List<TestRunService.RecentRun> recent(){return runs.recent();}
    @GetMapping("/projects/{projectId}/test-runs") List<TestRunService.RunView> list(@PathVariable String projectId){return runs.list(projectId);}
    @GetMapping(value="/test-runs/{runId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter events(@PathVariable String runId){return runs.subscribe(runId);}
    @PostMapping("/test-runs/{runId}/cancel") TestRunService.RunView cancel(@PathVariable String runId){return runs.cancel(runId);}

    public record RunRequest(@NotBlank String projectId,@NotBlank String targetId,@NotBlank String suiteVersionId,List<String> modules,Boolean activeScan){}
}
