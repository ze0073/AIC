package com.shijie.accessibility.service;

import com.shijie.accessibility.common.*;
import com.shijie.accessibility.mapper.ArtifactMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReportSnapshotService {
    private final JdbcTemplate jdbc;
    private final ReportService reports;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ArtifactMapper artifacts;
    private final StorageService storage;
    public ReportSnapshotService(JdbcTemplate jdbc, ReportService reports, ObjectMapper json,
            PlatformTransactionManager transactions, ArtifactMapper artifacts, StorageService storage) {
        this.jdbc=jdbc; this.reports=reports; this.json=json; this.artifacts=artifacts; this.storage=storage;
        this.tx=new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Frozen create(String scanId,String key) {
        if(key==null||key.isBlank()||key.length()>120) throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED","需要 1–120 字符的 Idempotency-Key");
        return tx.execute(status->{
            // Serialize creation per scan before opening the consistent read view.
            var locked=jdbc.queryForList("SELECT id FROM scan_tasks WHERE id=? FOR UPDATE",String.class,scanId);
            if(locked.isEmpty()) throw ApiException.notFound("扫描任务不存在");
            var existing=jdbc.queryForList("SELECT id FROM report_snapshots WHERE scan_task_id=? AND idempotency_key=?",String.class,scanId,key);
            if(!existing.isEmpty()) return get(existing.getFirst());
            var report=reports.snapshot(scanId);
            if(!List.of("COMPLETED","PARTIAL").contains(report.scan().status))
                throw ApiException.conflict("SCAN_NOT_FINISHED","完成或部分完成的扫描才可生成报告快照");
            String payload=json.writeValueAsString(report), hash=Hashes.sha256(payload), id=Ulids.next();
            jdbc.update("INSERT INTO report_snapshots(id,scan_task_id,idempotency_key,content_hash,payload,created_at) VALUES(?,?,?,?,?,?)",
                    id,scanId,key,hash,payload,report.generatedAt());
            return new Frozen(id,hash,report.generatedAt(),report);
        });
    }
    public Frozen get(String id) {
        var rows=jdbc.query("SELECT id,content_hash,payload FROM report_snapshots WHERE id=?",(rs,n)->{
            String payload=rs.getString("payload");
            if(!Hashes.sha256(payload).equals(rs.getString("content_hash"))) throw new IllegalStateException("报告快照校验失败");
            var report=json.readValue(payload,ReportService.Snapshot.class);
            return new Frozen(rs.getString("id"),rs.getString("content_hash"),report.generatedAt(),report);
        },id);
        if(rows.isEmpty()) throw ApiException.notFound("报告快照不存在");
        return rows.getFirst();
    }
    public List<Summary> list(String scanId) {
        return jdbc.query("SELECT id,content_hash,created_at,pdf_artifact_id,json_artifact_id FROM report_snapshots WHERE scan_task_id=? ORDER BY created_at DESC,id DESC",
                (rs,n)->new Summary(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toLocalDateTime(),rs.getString(4)!=null,rs.getString(5)!=null),scanId);
    }
    public Frozen legacy(String scanId) {
        var rows=list(scanId);
        return rows.isEmpty()?create(scanId,"legacy-initial"):get(rows.getFirst().snapshotId);
    }
    public synchronized ReportService.ReportFile export(String id,String format) throws Exception {
        if(!List.of("pdf","json").contains(format)) throw ApiException.badRequest("REPORT_FORMAT","只支持 pdf 或 json");
        Frozen frozen=get(id);
        String column=format+"_artifact_id";
        String artifactId=jdbc.queryForObject("SELECT "+column+" FROM report_snapshots WHERE id=?",String.class,id);
        if(artifactId!=null) {
            var artifact=artifacts.selectById(artifactId);
            if(artifact!=null) {
                var path=storage.resolveArtifact(artifact.relativePath);
                if(Files.isRegularFile(path)&&Hashes.sha256(path).equals(artifact.sha256))
                    return new ReportService.ReportFile(path,"pdf".equals(format)?"application/pdf":"application/json","accessibility-report-"+id+"."+format,artifactId);
            }
        }
        var file=reports.generate(frozen.report,id,frozen.contentHash,format);
        jdbc.update("UPDATE report_snapshots SET "+column+"=? WHERE id=?",file.artifactId(),id);
        return file;
    }
    public record Summary(String snapshotId,String contentHash,LocalDateTime generatedAt,boolean pdfReady,boolean jsonReady) {}
    public record Frozen(String snapshotId,String contentHash,LocalDateTime generatedAt,ReportService.Snapshot report) {}
}
