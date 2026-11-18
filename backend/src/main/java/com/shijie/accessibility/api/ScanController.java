package com.shijie.accessibility.api;

import com.shijie.accessibility.model.ScanLogEntity;
import com.shijie.accessibility.model.ScanTaskEntity;
import com.shijie.accessibility.service.ScanService;
import com.shijie.accessibility.service.ReviewService;
import com.shijie.accessibility.service.IdempotencyService;
import com.shijie.accessibility.service.ProjectService;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ScanController {
    private final ScanService service;
    private final ReviewService reviews;
    private final IdempotencyService idempotency;
    private final ProjectAccessService access;
    private final ProjectService projects;

    public ScanController(ScanService service, ReviewService reviews, IdempotencyService idempotency,
                          ProjectAccessService access, ProjectService projects) {
        this.service = service;
        this.reviews = reviews;
        this.idempotency = idempotency;
        this.access = access;
        this.projects = projects;
    }

    @PostMapping("/projects/{projectId}/scans")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @org.springframework.transaction.annotation.Transactional
    ScanTaskEntity create(@PathVariable String projectId, @RequestBody(required = false) ScanService.ScanRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        access.require(projectId,"MEMBER");
        if(key!=null){var previous=idempotency.find(key,"create-scan");if(previous.isPresent())return service.get(previous.get());}
        if (request == null) request = new ScanService.ScanRequest(List.of(), false, false, true, ScanService.ALL_RULES);
        var scan=service.create(projectId, request);if(key!=null)idempotency.save(key,"create-scan",scan.id);return scan;
    }

    @GetMapping("/projects/{projectId}/scans")
    List<ScanTaskEntity> recent(@PathVariable String projectId) { access.require(projectId,"VIEWER"); return service.recentForProject(projectId); }

    @GetMapping("/scans/{scanId}")
    ScanTaskEntity get(@PathVariable String scanId) { ScanTaskEntity scan=service.get(scanId); access.require(scan.projectId,"VIEWER"); return scan; }

    @GetMapping(value = "/scans/{scanId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String scanId) { ScanTaskEntity scan=service.get(scanId); access.require(scan.projectId,"VIEWER"); return service.subscribe(scanId); }

    @PostMapping("/scans/{scanId}/cancel")
    ScanTaskEntity cancel(@PathVariable String scanId) { ScanTaskEntity scan=service.get(scanId); access.require(scan.projectId,"MEMBER"); return service.cancel(scanId); }

    @PostMapping("/versions/{versionId}/rescan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ScanTaskEntity rescan(@PathVariable String versionId, @RequestBody(required = false) ScanService.ScanRequest request) {
        access.require(projects.version(versionId).projectId,"MEMBER");
        if (request == null) request = new ScanService.ScanRequest(List.of(), false, false, true, ScanService.ALL_RULES);
        return service.rescan(versionId, request);
    }

    @GetMapping("/scans/{scanId}/logs")
    List<ScanLogEntity> logs(@PathVariable String scanId) { ScanTaskEntity scan=service.get(scanId); access.require(scan.projectId,"VIEWER"); return service.logs(scanId); }

    @GetMapping("/scans/{scanId}/findings")
    ScanService.FindingsPage findings(@PathVariable String scanId,
                                      @RequestParam(required = false) String severity,
                                      @RequestParam(required = false) String ruleId,
                                      @RequestParam(required = false) String pagePath,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String source,
                                      @RequestParam(defaultValue = "severity") String sort,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "30") int size) {
        ScanTaskEntity scan=service.get(scanId); access.require(scan.projectId,"VIEWER");
        return service.listFindings(scanId, severity, ruleId, pagePath, status, source, sort, page, size);
    }

    @GetMapping("/findings/{findingId}")
    ScanService.FindingDetail finding(@PathVariable String findingId) { ScanService.FindingDetail detail=service.finding(findingId); access.require(service.get(detail.finding().scanTaskId).projectId,"VIEWER"); return detail; }

    @GetMapping("/scans/{scanId}/analysis-traces")
    List<ScanService.AnalysisTraceView> traces(@PathVariable String scanId,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String pagePath) {
        ScanTaskEntity scan=service.get(scanId); access.require(scan.projectId,"VIEWER");
        return service.analysisTraces(scanId, type, pagePath);
    }

    @PostMapping("/findings/{findingId}/reviews")
    ReviewService.ReviewResult review(@PathVariable String findingId,
                                      @RequestHeader("Idempotency-Key") String key,
                                      @RequestBody ReviewService.ReviewRequest request) {
        ScanService.FindingDetail detail=service.finding(findingId); access.require(service.get(detail.finding().scanTaskId).projectId,"MEMBER");
        var previous = idempotency.find(key, "review-finding");
        if (previous.isPresent()) return reviews.resultByReview(previous.get());
        ReviewService.ReviewResult result = reviews.review(findingId, request);
        idempotency.save(key, "review-finding", result.review().id);
        return result;
    }
}
