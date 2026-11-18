package com.shijie.accessibility.api;

import com.shijie.accessibility.hosting.*;
import com.shijie.accessibility.service.*;
import com.shijie.accessibility.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

@RestController @RequestMapping("/api")
public class HostingController {
    @org.springframework.beans.factory.annotation.Autowired private com.shijie.accessibility.security.ProjectAccessService access;
    @org.springframework.beans.factory.annotation.Autowired private HostingDeletionService deletion;
    private final HostingService sites;private final HostingEvents events;private final HostingWorker worker;private final StorageService storage;private final ProjectService projects;private final DockerHostingRuntime runtime;private final JdbcTemplate jdbc;
    public HostingController(HostingService sites,HostingEvents events,HostingWorker worker,StorageService storage,ProjectService projects,DockerHostingRuntime runtime,JdbcTemplate jdbc){this.sites=sites;this.events=events;this.worker=worker;this.storage=storage;this.projects=projects;this.runtime=runtime;this.jdbc=jdbc;}
    @GetMapping("/hosting/environment") Object environment(){return sites.environment();}
    @GetMapping("/sites") Object list(){return sites.list();}
    @PostMapping("/sites") Object create(@RequestBody Create body,@RequestHeader("Idempotency-Key") String key){return sites.create(body.name(),body.purpose(),key);}
    @GetMapping("/sites/{id}") Object get(@PathVariable String id){return sites.get(id);}
    @DeleteMapping("/sites/{id}") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    Object delete(@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return deletion.request(id,key);}
    @GetMapping("/projects/{id}/hosted-site") Object project(@PathVariable String id){access.require(id,"VIEWER");var rows=jdbc.queryForList("SELECT id FROM hosted_sites WHERE project_id=?",id);return rows.isEmpty()?null:sites.get((String)rows.getFirst().get("id"));}
    @PostMapping("/sites/{id}/source") Object upload(@PathVariable String id,@RequestParam MultipartFile file,@RequestHeader("Idempotency-Key") String key)throws Exception{return sites.upload(id,file,key);}
    @PostMapping("/sites/{id}/repository") Object repository(@PathVariable String id,@RequestBody HostingService.RepositoryRequest body,@RequestHeader("Idempotency-Key") String key){return sites.repository(id,body,key);}
    @PostMapping("/source-jobs/{id}/cancel") Object sourceCancel(@PathVariable String id){worker.cancelSource(id);return Map.of("cancelled",true);}
    @PostMapping("/sites/{id}/configuration") Object configure(@PathVariable String id,@RequestBody HostingService.Configure body){return sites.configure(id,body);}
    @PostMapping("/sites/{id}/purpose") Object purpose(@PathVariable String id,@RequestBody Purpose body){return sites.purpose(id,body.purpose());}
    @PostMapping("/sites/{id}/deployments") Object deploy(@PathVariable String id,@RequestBody HostingService.DeployRequest body,@RequestHeader("Idempotency-Key") String key){return sites.deploy(id,body,key);}
    @GetMapping("/deployments/{id}") Object deployment(@PathVariable String id){return sites.deployment(id);}
    @GetMapping(value="/deployments/{id}/events",produces="text/event-stream") SseEmitter events(@PathVariable String id){var deployment=sites.deployment(id);var site=sites.rawSite(deployment.siteId());return events.subscribe(id,deployment,access.currentUser().id(),(String)site.get("project_id"),()->sites.deploymentInternal(id));}
    @PostMapping("/deployments/{id}/launch") Object launch(@PathVariable String id){return Map.of("url",sites.launch(id));}
    @PostMapping("/deployments/{id}/stop") Object stop(@PathVariable String id){return sites.stop(id);}
    @PostMapping("/deployments/{id}/retry") Object retry(@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return sites.retry(id,key);}
    @PostMapping("/sites/{id}/publish") Object publish(@PathVariable String id,@RequestBody Publish body,@RequestHeader("Idempotency-Key") String key){return sites.publish(id,body.deploymentId(),body.expectedCurrentId(),key);}
    @PostMapping("/sites/{id}/rollback") Object rollback(@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return sites.rollback(id,key);}
    @GetMapping("/sites/{id}/source-download") void source(@PathVariable String id,@RequestParam(required=false) String versionId,HttpServletResponse response)throws Exception{
        var site=sites.get(id);String version=versionId==null?site.sourceVersionId():versionId;if(version==null)throw ApiException.badRequest("SOURCE_REQUIRED","还没有源码");var v=projects.version(version);if(!v.projectId.equals(site.projectId()))throw ApiException.notFound("源码不存在");Path root=storage.versionSource(site.projectId(),version);response.setContentType("application/x-shijie-source");try(var zip=new ZipOutputStream(response.getOutputStream())){for(String file:storage.listRelativeFiles(root)){zip.putNextEntry(new ZipEntry(file));Files.copy(storage.resolveVersionFile(site.projectId(),version,file),zip);zip.closeEntry();}}
    }
    @GetMapping("/sites/{id}/backup") void backup(@PathVariable String id,@RequestParam(required=false) String deploymentId,HttpServletResponse response)throws Exception{
        var site=sites.get(id);if("DELETING".equals(site.deletionStatus()))throw ApiException.conflict("SITE_DELETING","网站正在删除，无法创建备份");String deployment=deploymentId==null?site.currentDeploymentId():deploymentId;if(deployment==null)throw ApiException.badRequest("DATABASE_REQUIRED","请先部署一个带数据库的网站");var d=sites.deployment(deployment);if(!id.equals(d.siteId()))throw ApiException.notFound("数据不存在");var raw=sites.rawDeployment(deployment);if(raw.get("database_resource_id")==null)throw ApiException.badRequest("DATABASE_REQUIRED","此版本没有数据库");var resource=jdbc.queryForMap("SELECT * FROM hosted_database_resources WHERE id=?",raw.get("database_resource_id"));Path destination=runtime.directory(deployment).resolve("backup-"+UUID.randomUUID()+".sql");try{runtime.backup(deployment,(String)resource.get("container_name"),(String)resource.get("engine"),destination);response.setContentType("application/x-shijie-data");Files.copy(destination,response.getOutputStream());}finally{Files.deleteIfExists(destination);}
    }
    public record Create(String name,String purpose){}
    @GetMapping("/sites/{id}/full-backup") void fullBackup(@PathVariable String id,@RequestParam(required=false) String deploymentId,HttpServletResponse response)throws Exception{
        var site=sites.get(id);if(!"ACTIVE".equals(site.deletionStatus()))throw ApiException.conflict("SITE_DELETING","网站正在清理，无法创建备份");
        String deployment=deploymentId==null?site.currentDeploymentId():deploymentId;if(deployment==null)throw ApiException.badRequest("DEPLOYMENT_REQUIRED","请选择一个运行过的网站版本");
        var d=sites.deployment(deployment);if(!id.equals(d.siteId()))throw ApiException.notFound("数据不存在");var raw=sites.rawDeployment(deployment);
        Path work=runtime.directory(deployment),files=work.resolve("files-"+UUID.randomUUID()+".tar.gz"),sql=work.resolve("database-"+UUID.randomUUID()+".sql"),source=work.resolve("source-"+UUID.randomUUID()+".zip");
        String engine="NONE";
        try{
            runtime.backupFiles(deployment,id,d.environmentKind(),files);
            if(raw.get("database_resource_id")!=null){var db=jdbc.queryForMap("SELECT * FROM hosted_database_resources WHERE id=?",raw.get("database_resource_id"));engine=(String)db.get("engine");runtime.backup(deployment,(String)db.get("container_name"),engine,sql);}
            Path sourceRoot=storage.versionSource(site.projectId(),raw.get("source_version_id").toString());
            try(var zip=new ZipOutputStream(Files.newOutputStream(source))){for(String name:storage.listRelativeFiles(sourceRoot)){zip.putNextEntry(new ZipEntry(name));Files.copy(storage.resolveVersionFile(site.projectId(),raw.get("source_version_id").toString(),name),zip);zip.closeEntry();}}
            var entries=new ArrayList<Map<String,Object>>();entries.add(backupEntry("website-files.tar.gz",files));entries.add(backupEntry("source.zip",source));if(Files.exists(sql))entries.add(backupEntry("database.sql",sql));
            var manifest=new LinkedHashMap<String,Object>();manifest.put("format","shijie-full-backup");manifest.put("schemaVersion",1);manifest.put("createdAt",java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString());manifest.put("siteId",id);manifest.put("deploymentId",deployment);manifest.put("environmentKind",d.environmentKind());manifest.put("sourceVersionId",raw.get("source_version_id"));manifest.put("sourceManifest",raw.get("source_manifest"));manifest.put("databaseEngine",engine);manifest.put("image",raw.get("image_digest"));manifest.put("frontendImage",raw.get("frontend_image_digest"));manifest.put("runtimeConfig",sites.config(raw.get("runtime_config")));manifest.put("consistency",Set.of("STOPPED","EXPIRED").contains(d.status())?"APPLICATION_STOPPED":"LIVE_NON_ATOMIC");manifest.put("files",entries);
            response.setContentType("application/zip");response.setHeader("Content-Disposition","attachment; filename=website-backup.zip");
            try(var zip=new ZipOutputStream(response.getOutputStream())){
                for(var entry:entries){String name=entry.get("name").toString();Path input=name.equals("source.zip")?source:name.equals("database.sql")?sql:files;zip.putNextEntry(new ZipEntry(name));Files.copy(input,zip);zip.closeEntry();}
                zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(new tools.jackson.databind.ObjectMapper().writeValueAsString(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
                zip.putNextEntry(new ZipEntry("backup-info.txt"));zip.write("网站源码、上传文件与数据库分别保存。manifest.json 包含大小与摘要。运行中备份不保证原子一致；恢复工具只创建新的隔离环境。运行密钥须另行提供。\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
        }finally{Files.deleteIfExists(files);Files.deleteIfExists(sql);Files.deleteIfExists(source);}
    }
    private Map<String,Object> backupEntry(String name,Path file)throws Exception{return Map.of("name",name,"size",Files.size(file),"sha256",com.shijie.accessibility.common.Hashes.sha256(file));}
    public record Purpose(String purpose){}
    public record Publish(String deploymentId,String expectedCurrentId){}
}
