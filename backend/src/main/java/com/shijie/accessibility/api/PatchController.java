package com.shijie.accessibility.api;

import com.shijie.accessibility.model.FixPatchEntity;
import com.shijie.accessibility.model.ProjectVersionEntity;
import com.shijie.accessibility.service.FixService;
import com.shijie.accessibility.service.IdempotencyService;
import com.shijie.accessibility.service.ProjectService;
import com.shijie.accessibility.service.ScanService;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api")
public class PatchController {
    private final FixService fixes;
    private final ProjectService projects;
    private final IdempotencyService idempotency;
    private final ScanService scans;
    private final ProjectAccessService access;

    public PatchController(FixService fixes, ProjectService projects, IdempotencyService idempotency,
                           ScanService scans, ProjectAccessService access) {
        this.fixes = fixes; this.projects = projects; this.idempotency = idempotency;
        this.scans=scans; this.access=access;
    }

    @PostMapping("/findings/{findingId}/fix-patches")
    @ResponseStatus(HttpStatus.CREATED)
    FixPatchEntity generate(@PathVariable String findingId, @RequestBody(required = false) GeneratePatch body) throws IOException {
        requireFinding(findingId,"MEMBER");
        GeneratePatch request = body == null ? new GeneratePatch("zh-CN", null, null, false) : body;
        return fixes.generate(findingId, new FixService.PatchRequest(
                request.language(), request.aiEvidenceId(), request.candidateText(), request.decorative()));
    }

    @GetMapping("/fix-patches/{patchId}")
    FixPatchEntity get(@PathVariable String patchId) { FixPatchEntity patch=fixes.get(patchId); requireFinding(patch.findingId,"VIEWER"); return patch; }

    @PostMapping("/fix-patches/{patchId}/apply")
    FixService.ApplyResult apply(@PathVariable String patchId, @RequestHeader("Idempotency-Key") String key,
                                 @RequestBody(required = false) ApplyPatch body) throws IOException {
        requireFinding(fixes.get(patchId).findingId,"MEMBER");
        var previous = idempotency.find(key, "apply-patch");
        if (previous.isPresent()) return new FixService.ApplyResult(fixes.get(previous.get()), null);
        FixService.ApplyResult result = fixes.apply(patchId, body != null && body.reviewAccepted());
        idempotency.save(key, "apply-patch", result.patch().id);
        return result;
    }

    @PostMapping("/fix-patches/{patchId}/reject")
    FixPatchEntity reject(@PathVariable String patchId, @RequestHeader("Idempotency-Key") String key) {
        requireFinding(fixes.get(patchId).findingId,"MEMBER");
        var previous = idempotency.find(key, "reject-patch");
        if (previous.isPresent()) return fixes.get(previous.get());
        FixPatchEntity result = fixes.reject(patchId);
        idempotency.save(key, "reject-patch", result.id);
        return result;
    }

    @PostMapping("/versions/{versionId}/rollback")
    ProjectVersionEntity rollback(@PathVariable String versionId, @RequestHeader("Idempotency-Key") String key) {
        access.require(projects.version(versionId).projectId,"MEMBER");
        var previous = idempotency.find(key, "rollback-version");
        if (previous.isPresent()) return projects.version(previous.get());
        ProjectVersionEntity result = projects.rollback(versionId);
        idempotency.save(key, "rollback-version", result.id);
        return result;
    }

    public record GeneratePatch(String language, String aiEvidenceId, String candidateText, Boolean decorative) {
        public GeneratePatch { decorative = Boolean.TRUE.equals(decorative); }
    }
    public record ApplyPatch(Boolean reviewAccepted) {
        public ApplyPatch { reviewAccepted = Boolean.TRUE.equals(reviewAccepted); }
    }

    private void requireFinding(String findingId,String role) {
        var detail=scans.finding(findingId);
        access.require(scans.get(detail.finding().scanTaskId).projectId,role);
    }
}
