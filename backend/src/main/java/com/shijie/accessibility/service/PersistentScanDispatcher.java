package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.atomic.AtomicBoolean;

/** The database is the queue; one oldest job at a time uses the existing scan executor. */
@Component
public class PersistentScanDispatcher {
    private final JdbcTemplate jdbc;private final ScanService scans;private final TestRunService runs;private final TaskExecutor executor;
    private final AtomicBoolean busy=new AtomicBoolean();
    @Value("${app.hosting.worker-only:false}") private boolean workerOnly;
    public PersistentScanDispatcher(JdbcTemplate jdbc,ScanService scans,TestRunService runs,@Qualifier("scanExecutor") TaskExecutor executor){this.jdbc=jdbc;this.scans=scans;this.runs=runs;this.executor=executor;}
    @Scheduled(fixedDelay=500,initialDelay=3000) void dispatch(){
        if(!busy.compareAndSet(false,true))return;
        try{
            String owner=workerOnly?"HOSTING_WORKER":"PLATFORM";
            String sql="SELECT id,kind FROM (SELECT id,'RUN' kind,created_at FROM test_runs WHERE status='PENDING' AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_snapshot,'$.executionOwner')),'PLATFORM')=?";
            if(!workerOnly)sql+=" UNION ALL SELECT id,'SCAN' kind,created_at FROM scan_tasks WHERE status='PENDING'";
            var jobs=jdbc.queryForList(sql+") pending ORDER BY created_at,id LIMIT 1",owner);
            if(jobs.isEmpty()){busy.set(false);return;}
            var job=jobs.getFirst();executor.execute(()->{try{if("SCAN".equals(job.get("kind")))scans.executePending(job.get("id").toString());else runs.executePending(job.get("id").toString());}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Persistent scan dispatch failed",e);}finally{busy.set(false);}});
        }catch(Exception e){busy.set(false);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Persistent queue unavailable: {}",e.getClass().getSimpleName());}
    }
    static void capacity(JdbcTemplate jdbc,boolean worker){
        String owner=worker?"HOSTING_WORKER":"PLATFORM";
        jdbc.queryForList("SELECT id FROM runtime_queue_locks WHERE id=? FOR UPDATE",owner);
        Integer count=jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM test_runs WHERE status IN ('PENDING','RUNNING') AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_snapshot,'$.executionOwner')),'PLATFORM')=?)"+(worker?"":"+(SELECT COUNT(*) FROM scan_tasks WHERE status IN ('PENDING','RUNNING'))"),Integer.class,owner);
        if(count!=null&&count>=50)throw ApiException.conflict("QUEUE_FULL","检测队列已满，请等待已有任务结束后再试");
    }
}
