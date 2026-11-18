package com.shijie.accessibility.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class RegressionScheduler {
    @org.springframework.beans.factory.annotation.Value("${app.hosting.worker-only:false}") private boolean workerOnly;
    private static final Logger log=LoggerFactory.getLogger(RegressionScheduler.class);
    private final JdbcTemplate jdbc;private final TestRunService runs;
    public RegressionScheduler(JdbcTemplate jdbc,TestRunService runs){this.jdbc=jdbc;this.runs=runs;}

    @Scheduled(fixedDelay=60_000,initialDelay=15_000)
    @Transactional
    public void enqueueDuePlans(){
        if(workerOnly)return;
        var plans=jdbc.queryForList("SELECT * FROM regression_plans WHERE enabled=TRUE AND (next_run_at IS NULL OR next_run_at<=?) ORDER BY next_run_at LIMIT 10 FOR UPDATE",LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        for(var plan:plans){String id=String.valueOf(plan.get("id"));try{String runId=runs.createScheduled(String.valueOf(plan.get("project_id")),String.valueOf(plan.get("target_id")),String.valueOf(plan.get("suite_version_id")),Boolean.TRUE.equals(plan.get("active_scan_enabled")),Boolean.TRUE.equals(plan.get("upload_enabled")),Boolean.TRUE.equals(plan.get("load_enabled")),plan.get("created_by")==null?null:plan.get("created_by").toString());LocalDateTime now=LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));LocalDateTime next=RegressionSchedule.next(String.valueOf(plan.get("cron_expression")));jdbc.update("UPDATE regression_plans SET last_run_at=?,next_run_at=?,updated_at=? WHERE id=?",now,next,now,id);log.info("Scheduled regression run {} from plan {}",runId,id);}catch(Exception ex){log.warn("Unable to schedule regression plan {}: {}",id,ex.getMessage());jdbc.update("UPDATE regression_plans SET next_run_at=?,updated_at=? WHERE id=?",LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusHours(1),LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),id);}}
    }
}
