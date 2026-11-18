package com.shijie.accessibility.api;

import com.shijie.accessibility.mapper.ArtifactMapper;
import com.shijie.accessibility.model.ArtifactEntity;
import com.shijie.accessibility.service.ReportService;
import com.shijie.accessibility.service.ReportSnapshotService;
import com.shijie.accessibility.service.StorageService;
import com.shijie.accessibility.service.ScanService;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api")
public class ReportController {
    private final ReportService reports;
    private final ArtifactMapper artifacts;
    private final StorageService storage;
    private final ReportSnapshotService snapshots;
    private final ScanService scans;
    private final ProjectAccessService access;

    public ReportController(ReportService reports, ArtifactMapper artifacts, StorageService storage, ReportSnapshotService snapshots,
                            ScanService scans, ProjectAccessService access) {
        this.reports = reports; this.artifacts = artifacts; this.storage = storage;
        this.snapshots=snapshots;
        this.scans=scans; this.access=access;
    }

    @GetMapping("/scans/{scanId}/report-snapshot")
    ResponseEntity<ReportService.Snapshot> snapshot(@PathVariable String scanId) {
        access.require(scans.get(scanId).projectId,"VIEWER");
        var frozen=snapshots.legacy(scanId);
        return ResponseEntity.ok().header("X-Report-Snapshot-Id",frozen.snapshotId()).body(frozen.report());
    }

    @PostMapping("/scans/{scanId}/report-snapshots")
    ReportSnapshotService.Frozen create(@PathVariable String scanId,@RequestHeader("Idempotency-Key") String key) { access.require(scans.get(scanId).projectId,"MEMBER"); return snapshots.create(scanId,key); }

    @GetMapping("/scans/{scanId}/report-snapshots")
    java.util.List<ReportSnapshotService.Summary> list(@PathVariable String scanId) { access.require(scans.get(scanId).projectId,"VIEWER"); return snapshots.list(scanId); }

    @GetMapping("/report-snapshots/{snapshotId}")
    ReportSnapshotService.Frozen frozen(@PathVariable String snapshotId) { var result=snapshots.get(snapshotId); access.require(result.report().project().id,"VIEWER"); return result; }

    @GetMapping("/report-snapshots/{snapshotId}/export")
    ResponseEntity<FileSystemResource> export(@PathVariable String snapshotId,@RequestParam(defaultValue="pdf") String format) throws Exception {
        access.require(snapshots.get(snapshotId).report().project().id,"VIEWER");
        return download(snapshots.export(snapshotId,format),snapshotId);
    }

    @GetMapping("/scans/{scanId}/report")
    ResponseEntity<FileSystemResource> report(@PathVariable String scanId,
                                               @RequestParam(defaultValue = "pdf") String format) throws Exception {
        access.require(scans.get(scanId).projectId,"VIEWER");
        var frozen=snapshots.legacy(scanId);
        return download(snapshots.export(frozen.snapshotId(),format),frozen.snapshotId());
    }

    private ResponseEntity<FileSystemResource> download(ReportService.ReportFile report,String snapshotId) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(report.contentType()))
                .header("X-Report-Snapshot-Id",snapshotId)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(report.filename(), StandardCharsets.UTF_8).build().toString())
                .contentLength(Files.size(report.path())).body(new FileSystemResource(report.path()));
    }

    @GetMapping("/artifacts/{artifactId}")
    ResponseEntity<FileSystemResource> artifact(@PathVariable String artifactId) throws Exception {
        ArtifactEntity artifact = artifacts.selectById(artifactId);
        if (artifact == null) throw com.shijie.accessibility.common.ApiException.notFound("工件不存在");
        access.require(artifact.projectId,"VIEWER");
        var path = storage.resolveArtifact(artifact.relativePath);
        MediaType type = MediaTypeFactory.getMediaType(path.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type).header("X-Content-Type-Options", "nosniff")
                .contentLength(Files.size(path)).body(new FileSystemResource(path));
    }
}
