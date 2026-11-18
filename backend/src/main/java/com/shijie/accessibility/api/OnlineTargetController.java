package com.shijie.accessibility.api;

import com.shijie.accessibility.online.OnlineTargetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class OnlineTargetController {
    private final OnlineTargetService targets;
    public OnlineTargetController(OnlineTargetService targets) { this.targets = targets; }

    @GetMapping("/targets")
    List<OnlineTargetService.TargetView> targets(@PathVariable String projectId) { return targets.list(projectId); }

    @GetMapping("/target-scope")
    com.shijie.accessibility.online.TargetScopeService.ValidatedTarget previewScope(@PathVariable String projectId,
                                                                                   @RequestParam String baseUrl) {
        return targets.preview(projectId, baseUrl);
    }

    @PostMapping("/targets")
    @ResponseStatus(HttpStatus.CREATED)
    OnlineTargetService.TargetView createTarget(@PathVariable String projectId, @Valid @RequestBody TargetRequest body) {
        return targets.create(projectId, new OnlineTargetService.CreateTarget(body.name(), body.baseUrl(), body.allowedAddresses(),
                body.pathPrefixes(), Boolean.TRUE.equals(body.allowActiveScan()), Boolean.TRUE.equals(body.allowWriteTests()), body.cleanupInstructions()));
    }

    @GetMapping("/credentials")
    List<OnlineTargetService.CredentialView> credentials(@PathVariable String projectId) { return targets.credentials(projectId); }

    @PostMapping("/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    OnlineTargetService.CredentialView createCredential(@PathVariable String projectId, @Valid @RequestBody CredentialRequest body) {
        return targets.createCredential(projectId, new OnlineTargetService.CreateCredential(body.targetId(), body.name(),
                body.principalType(), body.authType(), body.secret()));
    }

    public record TargetRequest(@NotBlank @Size(max=120) String name, @NotBlank @Size(max=1000) String baseUrl,
                                @NotEmpty @Size(max=16) List<@NotBlank String> allowedAddresses,
                                @Size(max=20) List<@NotBlank String> pathPrefixes,
                                Boolean allowActiveScan, Boolean allowWriteTests,
                                @Size(max=2000) String cleanupInstructions) {}
    public record CredentialRequest(@NotBlank String targetId, @NotBlank @Size(max=120) String name,
                                    @NotBlank String principalType, @NotBlank String authType,
                                    @NotBlank @Size(max=16000) String secret) {}
}
