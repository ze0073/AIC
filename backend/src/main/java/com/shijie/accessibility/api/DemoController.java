package com.shijie.accessibility.api;

import com.shijie.accessibility.service.*;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class DemoController {
    private final DemoService demos;
    private final ComparisonService comparisons;
    private final ProjectAccessService access;
    public DemoController(DemoService demos,ComparisonService comparisons,ProjectAccessService access){this.demos=demos;this.comparisons=comparisons;this.access=access;}
    @GetMapping("/demo-state")
    DemoService.State state(@PathVariable String projectId,@RequestParam(required=false) String versionId,@RequestParam(required=false) String scanId){
        access.require(projectId,"VIEWER"); return demos.state(projectId,versionId,scanId);
    }
    @GetMapping("/comparison")
    ComparisonService.Result comparison(@PathVariable String projectId,@RequestParam String beforeScanId,@RequestParam String afterScanId){
        access.require(projectId,"VIEWER"); return comparisons.compare(projectId,beforeScanId,afterScanId);
    }
}
