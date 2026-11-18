package com.shijie.accessibility.api;

import com.shijie.accessibility.service.ExampleService;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/examples")
public class ExampleController {
    private final ExampleService examples;
    private final ProjectAccessService access;
    public ExampleController(ExampleService examples, ProjectAccessService access) { this.examples = examples; this.access = access; }

    @GetMapping
    List<Map<String, String>> list() { return examples.list(); }

    @PostMapping("/{slug}/install")
    @ResponseStatus(HttpStatus.CREATED)
    ExampleService.InstalledExample install(@PathVariable String slug) {
        ExampleService.InstalledExample installed = examples.install(slug);
        access.addOwner(installed.project().id);
        return installed;
    }
}
