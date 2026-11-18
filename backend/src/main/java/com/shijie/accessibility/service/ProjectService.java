package com.shijie.accessibility.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.mapper.ProjectMapper;
import com.shijie.accessibility.mapper.ProjectVersionMapper;
import com.shijie.accessibility.mapper.FixPatchMapper;
import com.shijie.accessibility.model.FixPatchEntity;
import com.shijie.accessibility.model.ProjectEntity;
import com.shijie.accessibility.model.ProjectVersionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class ProjectService {
    private final ProjectMapper projects;
    private final ProjectVersionMapper versions;
    private final FixPatchMapper patches;
    private final StorageService storage;
    @org.springframework.beans.factory.annotation.Autowired
    private com.shijie.accessibility.security.AuthService auth;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public void requireQuota() { auth.requireProjectQuota(); }

    public ProjectService(ProjectMapper projects, ProjectVersionMapper versions, FixPatchMapper patches, StorageService storage) {
        this.projects = projects;
        this.versions = versions;
        this.patches = patches;
        this.storage = storage;
    }

    @Transactional public ProjectEntity create(String name, String description) {
        if(auth!=null)auth.requireProjectQuota();
        ProjectEntity entity = new ProjectEntity();
        entity.id = Ulids.next();
        entity.name = name.strip();
        entity.description = description == null ? null : description.strip();
        entity.type = "STATIC_WEB";
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = entity.createdAt;
        projects.insert(entity);
        if(auth!=null&&jdbc!=null)jdbc.update("INSERT INTO project_members(project_id,user_id,role,created_at) VALUES(?,?,'ADMIN',?)",entity.id,auth.current().id(),LocalDateTime.now());
        return entity;
    }

    public List<ProjectEntity> list() {
        return projects.selectList(new QueryWrapper<ProjectEntity>().orderByDesc("updated_at"));
    }

    public ProjectEntity get(String id) {
        ProjectEntity entity = projects.selectById(id);
        if (entity == null) throw ApiException.notFound("项目不存在");
        return entity;
    }

    @Transactional
    public ProjectVersionEntity importProject(String projectId, MultipartFile file, String entryPage) {
        ProjectEntity project = get(projectId);
        String versionId = Ulids.next();
        StorageService.ImportedSource imported = storage.importUpload(file, projectId, versionId, entryPage);
        int versionNo = versions.selectList(new QueryWrapper<ProjectVersionEntity>()
                        .eq("project_id", projectId))
                .stream().map(v -> v.versionNo).max(Comparator.naturalOrder()).orElse(0) + 1;
        ProjectVersionEntity version = new ProjectVersionEntity();
        version.id = versionId;
        version.projectId = projectId;
        version.versionNo = versionNo;
        version.sourceType = imported.sourceType();
        version.storagePath = imported.storagePath();
        version.entryPage = imported.entryPage();
        version.manifestHash = imported.manifestHash();
        version.createdAt = LocalDateTime.now();
        versions.insert(version);
        project.currentVersionId = version.id;
        project.updatedAt = LocalDateTime.now();
        projects.updateById(project);
        return version;
    }

    public List<ProjectVersionEntity> versions(String projectId) {
        get(projectId);
        return versions.selectList(new QueryWrapper<ProjectVersionEntity>()
                .eq("project_id", projectId).orderByDesc("version_no"));
    }

    public ProjectVersionEntity version(String versionId) {
        ProjectVersionEntity version = versions.selectById(versionId);
        if (version == null) throw ApiException.notFound("项目版本不存在");
        return version;
    }

    public List<String> files(String projectId, String versionId) {
        ProjectVersionEntity version = version(versionId);
        if (!version.projectId.equals(projectId)) throw ApiException.notFound("项目版本不存在");
        try { return storage.listRelativeFiles(storage.versionSource(projectId, versionId)); }
        catch (Exception e) { throw new IllegalStateException("无法读取项目文件", e); }
    }

    public Path previewFile(String projectId, String versionId, String relative) {
        ProjectVersionEntity version = version(versionId);
        if (!version.projectId.equals(projectId)) throw ApiException.notFound("项目版本不存在");
        if (version.sourceType.startsWith("REPAIR_")||version.sourceType.startsWith("HOSTED_")) throw ApiException.badRequest("SOURCE_PREVIEW_DISABLED", "项目源码请从网站或修复工作台查看和下载");
        return storage.resolveVersionFile(projectId, versionId, relative);
    }

    @Transactional
    public void delete(String projectId) {
        get(projectId);
        if(jdbc!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM hosted_sites WHERE project_id=?",Integer.class,projectId)>0)throw ApiException.conflict("HOSTED_PROJECT","此项目包含托管网站，请从网站工作区停止运行；源码和检测记录会保留");
        projects.deleteById(projectId);
        storage.deleteProjectAsync(projectId);
    }

    @Transactional
    public ProjectVersionEntity rollback(String versionId) {
        ProjectVersionEntity version = version(versionId);
        if (version.sourceType.startsWith("REPAIR_")||version.sourceType.startsWith("HOSTED_")) throw ApiException.badRequest("SOURCE_NOT_PREVIEW", "源码版本请从网站工作区部署后查看");
        ProjectEntity project = get(version.projectId);
        if (project.currentVersionId != null) {
            for (FixPatchEntity patch : patches.selectList(new QueryWrapper<FixPatchEntity>()
                    .eq("new_version_id", project.currentVersionId).eq("base_version_id", version.id)
                    .in("status", List.of("APPLIED", "VERIFIED")))) {
                patch.status = "ROLLED_BACK";
                patch.updatedAt = LocalDateTime.now();
                patches.updateById(patch);
            }
        }
        project.currentVersionId = version.id;
        project.updatedAt = LocalDateTime.now();
        projects.updateById(project);
        return version;
    }
}
