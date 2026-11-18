package com.shijie.accessibility.hosting;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.security.AuthService;
import com.shijie.accessibility.security.ProjectAccessService;
import com.shijie.accessibility.service.IdempotencyService;
import com.shijie.accessibility.service.StorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;

/** Deletion remains in the database until the independent worker has removed all resources. */
@Service
public class HostingDeletionService {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final ProjectAccessService access;
    private final IdempotencyService idempotency;
    private final DockerHostingRuntime runtime;
    private final StorageService storage;
    private final TransactionTemplate transactions;

    public HostingDeletionService(JdbcTemplate jdbc, AuthService auth, ProjectAccessService access,
            IdempotencyService idempotency, DockerHostingRuntime runtime, StorageService storage,
            PlatformTransactionManager tx) {
        this.jdbc=jdbc; this.auth=auth; this.access=access; this.idempotency=idempotency;
        this.runtime=runtime; this.storage=storage; this.transactions=new TransactionTemplate(tx);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> request(String id, String key) {
        String operation="hosting-delete:"+auth.current().id()+":"+id;
        if (idempotency.find(key,operation).isPresent()) return Map.of("id",id,"accepted",true);
        var rows=jdbc.queryForList("SELECT * FROM hosted_sites WHERE id=?",id);
        if (rows.isEmpty()) throw ApiException.notFound("网站不存在");
        var site=rows.getFirst(); String project=(String)site.get("project_id");
        // VIEWER allows a failed deletion to be retried; authorization still requires project ADMIN.
        access.require(project,"VIEWER");
        Integer admins=jdbc.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=? AND role='ADMIN'",Integer.class,project,auth.current().id());
        if (admins==null || admins==0) throw ApiException.forbidden("只有项目管理员可以删除网站");
        jdbc.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE",project);
        rows=jdbc.queryForList("SELECT * FROM hosted_sites WHERE id=? FOR UPDATE",id);
        if (rows.isEmpty()) throw ApiException.notFound("网站不存在");
        requireIdle(id,project);
        jdbc.update("UPDATE hosted_sites SET deletion_status='DELETING',deletion_error=NULL,updated_at=? WHERE id=?",LocalDateTime.now(),id);
        // Close public and private entrances as soon as deletion is confirmed.
        jdbc.update("UPDATE hosted_deployments SET published=FALSE,cancel_requested=TRUE WHERE site_id=?",id);
        idempotency.save(key,operation,id);
        return Map.of("id",id,"accepted",true);
    }

    void requireIdle(String id,String project) {
        Integer imports=jdbc.queryForObject("SELECT COUNT(*) FROM hosted_source_jobs WHERE site_id=? AND status IN ('QUEUED','IMPORTING')",Integer.class,id);
        Integer deployments=jdbc.queryForObject("SELECT COUNT(*) FROM hosted_deployments WHERE site_id=? AND status IN ('QUEUED','BUILDING','STARTING','CHECKING')",Integer.class,id);
        Integer tests=jdbc.queryForObject("SELECT COUNT(*) FROM test_runs WHERE project_id=? AND status IN ('PENDING','RUNNING')",Integer.class,project);
        Integer scans=jdbc.queryForObject("SELECT COUNT(*) FROM scan_tasks WHERE project_id=? AND status IN ('PENDING','RUNNING')",Integer.class,project);
        Integer agents=jdbc.queryForObject("SELECT COUNT(*) FROM agent_tasks WHERE project_id=? AND status='RUNNING'",Integer.class,project);
        if (positive(imports)||positive(deployments)||positive(tests)||positive(scans)||positive(agents))
            throw ApiException.conflict("SITE_BUSY","网站还有导入、构建或检测任务。请先取消任务，等待任务结束后再删除；运行中的网站会在删除时自动停止。");
    }

    private boolean positive(Integer value) { return value!=null && value>0; }

    /** Only called from the hosting worker's single execution thread, also after a restart. */
    boolean processNext() {
        var rows=jdbc.queryForList("SELECT id,project_id FROM hosted_sites WHERE deletion_status='DELETING' ORDER BY updated_at LIMIT 1");
        if (rows.isEmpty()) return false;
        String id=(String)rows.getFirst().get("id"), project=(String)rows.getFirst().get("project_id");
        try {
            requireIdle(id,project);
            var deployments=jdbc.queryForList("SELECT id FROM hosted_deployments WHERE site_id=?",id);
            var databases=jdbc.queryForList("SELECT id FROM hosted_database_resources WHERE site_id=?",id);
            var imports=jdbc.queryForList("SELECT id FROM hosted_source_jobs WHERE site_id=?",id);
            var runs=jdbc.queryForList("SELECT id FROM test_runs WHERE project_id=?",project);
            runtime.deleteWebsite(id,deployments.stream().map(r->(String)r.get("id")).toList(),databases.stream().map(r->(String)r.get("id")).toList());
            for (var job:imports) storage.deleteTreeStrict(runtime.directory((String)job.get("id")));
            for (var run:runs) storage.deleteTestRun((String)run.get("id"));
            storage.deleteProject(project);
            transactions.executeWithoutResult(tx->{
                // Remove restrictive references before project cascades remove reports, versions and members.
                jdbc.update("DELETE FROM agent_tasks WHERE project_id=?",project);
                jdbc.update("DELETE FROM regression_plans WHERE project_id=?",project);
                jdbc.update("DELETE FROM test_runs WHERE project_id=?",project);
                jdbc.update("DELETE FROM hosted_deployments WHERE site_id=?",id);
                jdbc.update("DELETE FROM hosted_source_jobs WHERE site_id=?",id);
                jdbc.update("DELETE FROM hosted_database_resources WHERE site_id=?",id);
                jdbc.update("DELETE FROM hosted_sites WHERE id=?",id);
                jdbc.update("DELETE FROM projects WHERE id=?",project);
            });
        } catch (Exception failure) {
            // Keep ownership and resource IDs for a safe retry; never claim deletion succeeded.
            String reason=failure instanceof ApiException ? failure.getMessage() : "清理未完成，请检查运行引擎和文件占用后重试。已清理的资源不会重复删除。";
            jdbc.update("UPDATE hosted_sites SET deletion_status='DELETE_FAILED',deletion_error=?,updated_at=? WHERE id=?",reason,LocalDateTime.now(),id);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("网站 {} 删除未完成",id,failure);
        }
        return true;
    }
}
