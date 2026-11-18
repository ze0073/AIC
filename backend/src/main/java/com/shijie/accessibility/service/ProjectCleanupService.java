package com.shijie.accessibility.service;

import com.shijie.accessibility.common.*;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ProjectCleanupService {
    private final JdbcTemplate jdbc;private final ProjectAccessService access;private final StorageService storage;private final IdempotencyService idempotency;private final TransactionTemplate tx;
    private final AtomicBoolean busy=new AtomicBoolean();
    @Value("${app.hosting.worker-only:false}") private boolean workerOnly;
    public ProjectCleanupService(JdbcTemplate jdbc,ProjectAccessService access,StorageService storage,IdempotencyService idempotency,PlatformTransactionManager manager){this.jdbc=jdbc;this.access=access;this.storage=storage;this.idempotency=idempotency;this.tx=new TransactionTemplate(manager);}
    @Transactional public Map<String,Object> request(String project,String key){
        String operation="delete-project";
        var previous=idempotency.find(key,operation);if(previous.isPresent())return get(previous.get());
        access.require(project,"VIEWER");
        Integer admin=jdbc.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=? AND role='ADMIN'",Integer.class,project,access.currentUser().id());
        if(admin==null||admin==0)throw ApiException.forbidden("只有项目管理员可以删除项目");
        jdbc.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE",project);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM hosted_sites WHERE project_id=?",Integer.class,project)>0)throw ApiException.conflict("HOSTED_PROJECT","请从我的网站工作区删除托管网站");
        // Current locking reads see jobs committed while this request waited on the project lock.
        boolean active=!jdbc.queryForList("SELECT id FROM scan_tasks WHERE project_id=? AND status IN ('PENDING','RUNNING') FOR UPDATE",project).isEmpty();
        active|=!jdbc.queryForList("SELECT id FROM test_runs WHERE project_id=? AND status IN ('PENDING','RUNNING') FOR UPDATE",project).isEmpty();
        active|=!jdbc.queryForList("SELECT id FROM agent_tasks WHERE project_id=? AND status='RUNNING' FOR UPDATE",project).isEmpty();
        if(active)throw ApiException.conflict("PROJECT_BUSY","请先取消检测任务并等待结束，再删除项目");
        var jobs=jdbc.queryForList("SELECT id FROM project_cleanup_jobs WHERE project_id=? FOR UPDATE",project);String id=jobs.isEmpty()?Ulids.next():jobs.getFirst().get("id").toString();
        jdbc.update("UPDATE projects SET deletion_status='DELETING' WHERE id=?",project);
        jdbc.update("UPDATE regression_plans SET enabled=FALSE WHERE project_id=?",project);
        jdbc.update("INSERT INTO project_cleanup_jobs(id,project_id,requested_by,status,created_at,updated_at) VALUES(?,?,?,'PENDING',?,?) ON DUPLICATE KEY UPDATE status='PENDING',error_message=NULL,updated_at=VALUES(updated_at)",id,project,access.currentUser().id(),LocalDateTime.now(),LocalDateTime.now());
        idempotency.save(key,operation,id);return get(id);
    }
    public Map<String,Object> get(String id){
        var rows=jdbc.queryForList("SELECT id,project_id AS projectId,requested_by AS requestedBy,status,error_message AS errorMessage,updated_at AS updatedAt FROM project_cleanup_jobs WHERE id=?",id);
        if(rows.isEmpty())throw ApiException.notFound("清理任务不存在");var job=rows.getFirst();
        if(!access.currentUser().id().equals(job.get("requestedBy")))access.require(job.get("projectId").toString(),"ADMIN");
        return job;
    }
    @Scheduled(fixedDelay=2000,initialDelay=5000) void process(){
        if(workerOnly||!busy.compareAndSet(false,true))return;
        try{
            var rows=jdbc.queryForList("SELECT id,project_id FROM project_cleanup_jobs WHERE status IN ('PENDING','RUNNING') ORDER BY created_at LIMIT 1");if(rows.isEmpty())return;
            String id=rows.getFirst().get("id").toString(),project=rows.getFirst().get("project_id").toString();
            try{
                jdbc.update("UPDATE project_cleanup_jobs SET status='RUNNING',updated_at=? WHERE id=?",LocalDateTime.now(),id);
                for(var run:jdbc.queryForList("SELECT id FROM test_runs WHERE project_id=?",project))storage.deleteTestRun(run.get("id").toString());
                storage.deleteProject(project);
                tx.executeWithoutResult(status->{jdbc.update("DELETE FROM agent_tasks WHERE project_id=?",project);jdbc.update("DELETE FROM regression_plans WHERE project_id=?",project);jdbc.update("DELETE FROM test_runs WHERE project_id=?",project);jdbc.update("DELETE FROM projects WHERE id=?",project);jdbc.update("UPDATE project_cleanup_jobs SET status='COMPLETED',error_message=NULL,updated_at=? WHERE id=?",LocalDateTime.now(),id);});
            }catch(Exception ex){jdbc.update("UPDATE project_cleanup_jobs SET status='FAILED',error_message=?,updated_at=? WHERE id=?","文件清理失败，请关闭占用文件的程序后重试",LocalDateTime.now(),id);jdbc.update("UPDATE projects SET deletion_status='DELETE_FAILED' WHERE id=?",project);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Project cleanup {} failed",id,ex);}
        }finally{busy.set(false);}
    }
}
