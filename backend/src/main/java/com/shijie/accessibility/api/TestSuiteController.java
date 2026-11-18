package com.shijie.accessibility.api;

import com.shijie.accessibility.service.TestSuiteService;
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
public class TestSuiteController {
    private final TestSuiteService suites;
    public TestSuiteController(TestSuiteService suites) { this.suites = suites; }

    @GetMapping("/projects/{projectId}/test-suites")
    List<TestSuiteService.SuiteView> list(@PathVariable String projectId) { return suites.list(projectId); }

    @PostMapping("/projects/{projectId}/test-suites")
    @ResponseStatus(HttpStatus.CREATED)
    TestSuiteService.SuiteView create(@PathVariable String projectId, @Valid @RequestBody SuiteRequest body) {
        return suites.create(projectId, body.name(), body.description(), body.definition());
    }

    @GetMapping("/projects/{projectId}/test-suites/{suiteId}")
    TestSuiteService.SuiteView get(@PathVariable String projectId, @PathVariable String suiteId) { return suites.get(projectId, suiteId); }

    @PostMapping("/test-suites/{suiteId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    TestSuiteService.SuiteVersion publish(@PathVariable String suiteId, @RequestHeader("Idempotency-Key") String key,
                                          @NotNull @RequestBody Map<String, Object> definition) {
        return suites.publish(suiteId, definition, key);
    }

    @GetMapping("/test-suite-versions/{versionId}")
    TestSuiteService.SuiteVersion version(@PathVariable String versionId) { return suites.version(versionId); }

    public record SuiteRequest(@NotBlank @Size(max=160) String name, @Size(max=1000) String description,
                               @NotNull Map<String, Object> definition) {}
}
