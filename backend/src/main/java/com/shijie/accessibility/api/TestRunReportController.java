package com.shijie.accessibility.api;

import com.shijie.accessibility.service.TestRunReportService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

@RestController
@RequestMapping("/api")
public class TestRunReportController {
    private final TestRunReportService reports;public TestRunReportController(TestRunReportService reports){this.reports=reports;}
    @PostMapping("/test-runs/{runId}/report-snapshots") TestRunReportService.Snapshot create(@PathVariable String runId,@RequestHeader("Idempotency-Key")String key){return reports.create(runId,key);}
    @GetMapping("/test-runs/{runId}/report-snapshots") List<TestRunReportService.Snapshot>list(@PathVariable String runId){return reports.list(runId);}
    @GetMapping("/test-run-report-snapshots/{snapshotId}") TestRunReportService.Snapshot get(@PathVariable String snapshotId){return reports.get(snapshotId);}
    @GetMapping("/test-run-report-snapshots/{snapshotId}/export") ResponseEntity<FileSystemResource>export(@PathVariable String snapshotId,@RequestParam(defaultValue="pdf")String format)throws Exception{var file=reports.export(snapshotId,format);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename(),StandardCharsets.UTF_8).build().toString()).contentLength(Files.size(file.path())).body(new FileSystemResource(file.path()));}
}
