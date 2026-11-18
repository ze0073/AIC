package com.shijie.accessibility.api;

import com.shijie.accessibility.model.ProjectEntity;
import com.shijie.accessibility.model.ProjectVersionEntity;
import com.shijie.accessibility.service.IdempotencyService;
import com.shijie.accessibility.service.ProjectService;
import com.shijie.accessibility.security.ProjectAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService service;
    private final IdempotencyService idempotency;
    private final ProjectAccessService access;
    @org.springframework.beans.factory.annotation.Autowired private com.shijie.accessibility.service.ProjectCleanupService cleanup;

    public ProjectController(ProjectService service, IdempotencyService idempotency, ProjectAccessService access) {
        this.service = service;
        this.idempotency = idempotency;
        this.access = access;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    ProjectEntity create(@Valid @RequestBody CreateProject request,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        access.currentUser();
        if(key!=null){var previous=idempotency.find(key,"create-project");if(previous.isPresent())return service.get(previous.get());}
        service.requireQuota();
        ProjectEntity project = service.create(request.name(), request.description());
        access.addOwner(project.id);
        if(key!=null)idempotency.save(key,"create-project",project.id);
        return project;
    }

    @GetMapping
    List<ProjectEntity> list() { return access.visibleProjects(service.list()); }

    @GetMapping("/{projectId}")
    ProjectEntity get(@PathVariable String projectId) { access.require(projectId, "VIEWER");var project=service.get(projectId);project.projectRole=access.currentRole(projectId);return project; }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Object delete(@PathVariable String projectId, @RequestHeader("Idempotency-Key") String key) {return cleanup.request(projectId,key);}
    @GetMapping("/cleanup/{id}") Object cleanup(@PathVariable String id){return cleanup.get(id);}

    @PostMapping(value = "/{projectId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ProjectVersionEntity upload(@PathVariable String projectId, @RequestPart("file") MultipartFile file,
                                @RequestParam(required = false) String entryPage,
                                @RequestHeader("Idempotency-Key") String key) {
        access.require(projectId, "MEMBER");
        var previous = idempotency.find(key, "import-project");
        if (previous.isPresent()) return service.version(previous.get());
        ProjectVersionEntity version = service.importProject(projectId, file, entryPage);
        idempotency.save(key, "import-project", version.id);
        return version;
    }

    @GetMapping("/{projectId}/versions")
    List<ProjectVersionEntity> versions(@PathVariable String projectId) { access.require(projectId, "VIEWER"); return service.versions(projectId); }

    @GetMapping("/{projectId}/files")
    Map<String, Object> files(@PathVariable String projectId, @RequestParam String versionId) {
        access.require(projectId, "VIEWER");
        ProjectVersionEntity version = service.version(versionId);
        return Map.of("versionId", versionId, "entryPage", version.entryPage,
                "files", service.files(projectId, versionId));
    }

    @GetMapping("/{projectId}/versions/{versionId}/preview/{*assetPath}")
    ResponseEntity<FileSystemResource> preview(@PathVariable String projectId, @PathVariable String versionId,
                                               @PathVariable String assetPath) throws Exception {
        access.require(projectId, "VIEWER");
        String path = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;
        var file = service.previewFile(projectId, versionId, path);
        MediaType mediaType = MediaTypeFactory.getMediaType(file.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header("Content-Security-Policy",
                        "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(Files.size(file))
                .body(new FileSystemResource(file));
    }

    public record CreateProject(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description) {}

    @GetMapping("/{projectId}/members")
    List<ProjectAccessService.MemberView> members(@PathVariable String projectId) {
        return access.members(projectId);
    }

    @PostMapping("/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    List<ProjectAccessService.MemberView> addMember(@PathVariable String projectId, @Valid @RequestBody AddMember request) {
        access.addMember(projectId, request.userId(), request.role());
        return access.members(projectId);
    }

    public record AddMember(@NotBlank String userId, @NotBlank String role) {}
    @PatchMapping("/{projectId}/members/{userId}") List<ProjectAccessService.MemberView> role(@PathVariable String projectId,@PathVariable String userId,@Valid @RequestBody MemberRole body){access.addMember(projectId,userId,body.role());return access.members(projectId);}
    @DeleteMapping("/{projectId}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@PathVariable String projectId,@PathVariable String userId){access.removeMember(projectId,userId);}
    public record MemberRole(@NotBlank String role){}
    @PostMapping("/{projectId}/invite") Object invite(@PathVariable String projectId,@RequestBody Invite body){access.invite(projectId,body.username(),body.role());return access.members(projectId);}
    public record Invite(@NotBlank String username,@NotBlank String role){}
}
