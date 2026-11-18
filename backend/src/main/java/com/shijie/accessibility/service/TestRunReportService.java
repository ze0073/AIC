package com.shijie.accessibility.service;

import com.microsoft.playwright.*;
import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Hashes;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.config.AppProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class TestRunReportService {
    @org.springframework.beans.factory.annotation.Autowired private com.shijie.accessibility.security.ProjectAccessService access;
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final TestRunService runs;private final IdempotencyService idempotency;private final AppProperties properties;
    public TestRunReportService(JdbcTemplate jdbc,ObjectMapper json,TestRunService runs,IdempotencyService idempotency,AppProperties properties){this.jdbc=jdbc;this.json=json;this.runs=runs;this.idempotency=idempotency;this.properties=properties;}

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Snapshot create(String runId,String key){var previous=idempotency.find(key,"create-test-run-report");if(previous.isPresent())return get(previous.get());TestRunService.RunView run=runs.get(runId);if(!Set.of("COMPLETED","PARTIAL","FAILED","CANCELLED").contains(run.status()))throw ApiException.conflict("RUN_NOT_FINISHED","运行结束后才可生成不可变报告快照");Map<String,Object>report=new LinkedHashMap<>();report.put("schemaVersion","0.6.1");report.put("generatedAt",LocalDateTime.now());report.put("run",run);report.put("scope",read(run.configSnapshot()));report.put("tools",read(run.toolSnapshot()));report.put("issueAudit",jdbc.query("SELECT i.id,i.review_status,i.task_status,i.retest_run_id,i.recurrence_of_issue_id FROM run_issues i WHERE i.run_id=?",(rs,n)->{Map<String,Object>item=new LinkedHashMap<>();item.put("issueId",rs.getString("id"));item.put("reviewStatus",rs.getString("review_status"));item.put("taskStatus",rs.getString("task_status"));item.put("retestRunId",rs.getString("retest_run_id"));item.put("recurrenceOfIssueId",rs.getString("recurrence_of_issue_id"));item.put("history",jdbc.query("SELECT h.action,h.from_status,h.to_status,h.details,h.created_at,u.display_name operator FROM issue_history h LEFT JOIN app_users u ON u.id=h.user_id WHERE h.issue_id=? ORDER BY h.created_at",(history,index)->Map.of("action",Objects.toString(history.getString("action"),""),"oldValue",Objects.toString(history.getString("from_status"),""),"newValue",Objects.toString(history.getString("to_status"),""),"details",Objects.toString(history.getString("details"),""),"operator",Objects.toString(history.getString("operator"),""),"createdAt",history.getTimestamp("created_at").toLocalDateTime().toString()),rs.getString("id")));return item;},runId));report.put("scopeNotice","报告只覆盖配置快照中的目标、页面、接口、参数和负载；未执行项不表示通过。");report.put("securityNotice","引擎候选不会被自动确认为漏洞，可按需审阅；上传接受不代表代码执行；AI 解释不能确认漏洞。");String payload=write(report),hash=Hashes.sha256(payload),id=Ulids.next();jdbc.update("INSERT INTO test_run_report_snapshots(id,run_id,schema_version,idempotency_key,content_hash,payload,created_at) VALUES (?,?,?,?,?,?,?)",id,runId,"0.6.1",key,hash,payload,LocalDateTime.now());idempotency.save(key,"create-test-run-report",id);return get(id);}
    public Snapshot get(String id){List<Snapshot>rows=jdbc.query("SELECT * FROM test_run_report_snapshots WHERE id=?",(rs,n)->{String payload=rs.getString("payload");if(!Hashes.sha256(payload).equals(rs.getString("content_hash")))throw new IllegalStateException("测试运行报告快照校验失败");String runId=rs.getString("run_id");runs.get(runId);return new Snapshot(rs.getString("id"),runId,rs.getString("schema_version"),rs.getString("content_hash"),payload,rs.getTimestamp("created_at").toLocalDateTime(),rs.getString("json_path")!=null,rs.getString("pdf_path")!=null);},id);if(rows.isEmpty())throw ApiException.notFound("测试运行报告快照不存在");return rows.getFirst();}
    public List<Snapshot>list(String runId){runs.get(runId);return jdbc.query("SELECT * FROM test_run_report_snapshots WHERE run_id=? ORDER BY created_at DESC",(rs,n)->new Snapshot(rs.getString("id"),runId,rs.getString("schema_version"),rs.getString("content_hash"),null,rs.getTimestamp("created_at").toLocalDateTime(),rs.getString("json_path")!=null,rs.getString("pdf_path")!=null),runId);}
    @Transactional
    public synchronized ReportFile export(String id,String format)throws Exception{if(!List.of("json","pdf").contains(format))throw ApiException.badRequest("REPORT_FORMAT","只支持 pdf 或 json");Snapshot snapshot=get(id);if(access!=null)access.requireMutable(runs.get(snapshot.runId()).projectId());Path directory=properties.storageRoot().toAbsolutePath().normalize().resolve("test-runs").resolve(snapshot.runId()).resolve("reports");Files.createDirectories(directory);Path path=directory.resolve(id+"."+format);if(!Files.exists(path)){if("json".equals(format))Files.writeString(path,snapshot.payload());else renderPdf(snapshot,path);jdbc.update("UPDATE test_run_report_snapshots SET "+format+"_path=? WHERE id=?",properties.storageRoot().toAbsolutePath().normalize().relativize(path).toString().replace('\\','/'),id);}return new ReportFile(path,"pdf".equals(format)?"application/pdf":"application/json","quality-report-"+id+"."+format);}
    @SuppressWarnings("unchecked")
    void renderPdf(Snapshot snapshot,Path path){
        try {
            Map<String,Object> report=(Map<String,Object>)json.readValue(snapshot.payload(),Map.class);
            Map<String,Object> run=(Map<String,Object>)report.get("run");
            Map<String,Object> config=(Map<String,Object>)json.readValue(String.valueOf(run.get("configSnapshot")),Map.class);
            Map<String,Object> target=(Map<String,Object>)config.getOrDefault("target",Map.of());
            List<Map<String,Object>> modules=(List<Map<String,Object>>)run.getOrDefault("modules",List.of());
            List<Map<String,Object>> issues=(List<Map<String,Object>>)run.getOrDefault("issues",List.of());
            StringBuilder body=new StringBuilder("<h1>视界 · AI 网站质量综合报告</h1><p class='lead'>在线测试运行的范围、结果与自动候选证据</p>");
            body.append("<div class='meta'>快照 ID：").append(escape(snapshot.id())).append("<br>SHA-256：").append(escape(snapshot.contentHash())).append("<br>生成时间：").append(escape(snapshot.createdAt().toString())).append("</div>");
            body.append("<h2>检测范围</h2><table><tr><th>项目</th><th>内容</th></tr>");
            tableRow(body,"运行 ID",run.get("id"));tableRow(body,"运行状态",run.get("status"));tableRow(body,"目标地址",target.get("baseUrl"));
            tableRow(body,"模块",config.get("modules"));tableRow(body,"主动漏洞扫描",config.get("activeScan"));body.append("</table>");
            if("0.6.1".equals(report.get("schemaVersion"))){
                Map<String,Object> suite=(Map<String,Object>)config.getOrDefault("suite",Map.of());Map<String,Object> definition=(Map<String,Object>)suite.getOrDefault("definition",Map.of());
                body.append("<h2>范围与工具版本</h2><table><tr><th>项目</th><th>内容</th></tr>");
                tableRow(body,"套件版本",suite.get("versionNo"));tableRow(body,"页面",definition.get("pages"));tableRow(body,"接口用例",definition.get("apiCases"));tableRow(body,"负载配置",definition.get("performance"));
                tableRow(body,"工具版本与镜像",report.get("tools"));body.append("</table>");
            }
            body.append("<h2>模块结果</h2><table><tr><th>模块</th><th>状态</th><th>说明</th></tr>");
            for(Map<String,Object> module:modules){
                Map<String,Object> assessment=module.get("performanceAssessment") instanceof Map<?,?> value?(Map<String,Object>)value:Map.of();
                Object state=assessment.isEmpty()?module.get("status"):Objects.toString(module.get("status"),"")+" / 性能判定："+assessment.get("status");
                Object explanation=assessment.isEmpty()?module.get("summary"):Objects.toString(module.get("summary"),"")+"；"+assessment.get("reason");
                body.append("<tr><td>").append(escape(module.get("moduleType"))).append("</td><td>").append(escape(state)).append("</td><td>").append(escape(explanation)).append("</td></tr>");
            }
            body.append("</table>");
            if("0.6.1".equals(report.get("schemaVersion"))){
                for(Map<String,Object> module:modules) if("PERFORMANCE".equals(module.get("moduleType"))){
                    Map<String,Object> assessment=module.get("performanceAssessment") instanceof Map<?,?> value?(Map<String,Object>)value:Map.of();
                    boolean showLatency=!Boolean.FALSE.equals(assessment.get("latencyAvailable"));
                    Map<String,Object> payload=read(Objects.toString(module.get("resultPayload"),"{}"));Map<String,Object> summary=(Map<String,Object>)payload.getOrDefault("summary",Map.of());
                    Map<String,Object> metrics=(Map<String,Object>)summary.getOrDefault("metrics",Map.of());Map<String,Object> duration=(Map<String,Object>)metrics.getOrDefault("http_req_duration",Map.of());
                    Map<String,Object> values=duration.get("values") instanceof Map<?,?> nested?(Map<String,Object>)nested:duration;Map<String,Object> requests=(Map<String,Object>)metrics.getOrDefault("http_reqs",Map.of());Map<String,Object> failed=(Map<String,Object>)metrics.getOrDefault("http_req_failed",Map.of());
                    body.append("<h2>性能结果与时间序列</h2><table><tr><th>指标</th><th>结果</th></tr>");
                    tableRow(body,"P50 / P95 / P99 (ms)",showLatency?Objects.toString(values.get("med"),"—")+" / "+Objects.toString(values.get("p(95)"),"—")+" / "+Objects.toString(values.get("p(99)"),"—"):"— / — / —（无有效响应耗时）");
                    tableRow(body,Boolean.TRUE.equals(assessment.get("allRequestsFailed"))?"请求尝试速率 (次/秒)":"吞吐 (次/秒)",requests.get("rate"));tableRow(body,"错误率",failed.get("value") instanceof Number n?String.format(Locale.ROOT,"%.2f%%",n.doubleValue()*100):"—");tableRow(body,"首次超阈值阶段",summary.get("firstExceededStage"));body.append("</table>");
                    if(summary.get("timeSeries") instanceof List<?> series&&!series.isEmpty()){body.append("<table class='series'><tr><th>秒 / 阶段</th><th>P50 / P95 / P99 (ms)</th><th>吞吐 / 错误率</th></tr>");for(Object point:series)if(point instanceof Map<?,?> p){boolean validWindow=assessment.isEmpty()||!(p.get("errorRate") instanceof Number rate&&rate.doubleValue()==1);body.append("<tr><td>").append(escape(p.get("windowStartSecond"))).append(" / ").append(escape(p.get("stage"))).append("</td><td>").append(validWindow?escape(p.get("p50Ms"))+" / "+escape(p.get("p95Ms"))+" / "+escape(p.get("p99Ms")):"— / — / —").append("</td><td>").append(escape(p.get("throughputPerSecond"))).append(" / ").append(escape(p.get("errorRate"))).append("</td></tr>");}body.append("</table>");}
                }
            }
            body.append("<h2>问题与证据摘要（").append(issues.size()).append("）</h2>");
            if(issues.isEmpty())body.append("<p>未保存问题。请先确认所有应运行模块确已完整完成。</p>");
            for(Map<String,Object> issue:issues){body.append("<article><div class='label'>").append(escape(issue.get("severity"))).append(" · ").append(escape(issue.get("source"))).append(" · ").append(escape(issue.get("evidenceConfidence"))).append("</div><h3>").append(escape(issue.get("title"))).append("</h3><p>").append(escape(issue.get("description"))).append("</p><p class='small'>可选审阅：").append(escape(issue.get("reviewStatus"))).append("　修复状态：").append(escape(issue.get("taskStatus"))).append("</p></article>");}
            if(report.get("issueAudit") instanceof List<?> audits&&!audits.isEmpty()){
                body.append("<h2>可选审阅、复测与复发关联</h2><table><tr><th>问题 ID</th><th>审阅 / 任务 / 关联</th></tr>");
                for(Object item:audits)if(item instanceof Map<?,?> audit){body.append("<tr><td>").append(escape(audit.get("issueId"))).append("</td><td>").append(escape(audit.get("reviewStatus"))).append(" / ").append(escape(audit.get("taskStatus"))).append("<br>复测：").append(escape(audit.get("retestRunId"))).append("<br>复发源：").append(escape(audit.get("recurrenceOfIssueId")));if(audit.get("history") instanceof List<?> histories)for(Object entry:histories)if(entry instanceof Map<?,?> history&&"REVIEWED".equals(history.get("action"))){String details=Objects.toString(history.get("details"),"");Object reason=read(details).getOrDefault("reason",details);body.append("<br>可选审阅记录：").append(escape(history.get("operator"))).append(" ").append(escape(history.get("createdAt"))).append(" ").append(escape(reason));}body.append("</td></tr>");}body.append("</table>");
            }
            body.append("<section class='conclusion'><h2>结果口径</h2><p>").append(escape(report.get("scopeNotice"))).append("</p><p>").append(escape(report.get("securityNotice"))).append("</p><p class='small'>详细结构化证据保存在同一内容哈希对应的 JSON 快照；PDF 省略截图原始字节及过长响应，避免报告失去可读性。</p></section>");
            String html="""
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>
                @page{size:A4;margin:12mm 14mm 14mm}body{font-family:'Microsoft YaHei','Noto Sans CJK SC',sans-serif;color:#172b3a;font-size:11px;line-height:1.45}h1{font-size:22px;color:#12627b;margin:0 0 4px}h2{font-size:16px;color:#12627b;border-bottom:1px solid #a8cbd6;padding-bottom:3px;margin:9px 0 4px;break-after:avoid}h3{font-size:12px;margin:3px 0}.lead{color:#607b86;margin:0 0 8px}.meta{background:#edf5f7;border-left:4px solid #2c829c;padding:6px 12px;word-break:break-all}.label{font-size:10px;color:#b45135;font-weight:700}table{border-collapse:collapse;width:100%;table-layout:fixed}tr{break-inside:avoid}.series{break-inside:avoid}td,th{border:1px solid #d6e1e5;padding:3px 7px;text-align:left;vertical-align:top;word-break:break-word}th{background:#e8f1f4}th:first-child,td:first-child{width:25%}article{border:1px solid #dce7eb;padding:5px 8px;margin:4px 0;break-inside:avoid}article p{margin:1px 0}.small{font-size:9px;color:#607b86}.conclusion{break-inside:avoid}.conclusion p{margin:4px 0}
                </style></head><body>__BODY__</body></html>
                """.replace("__BODY__",body.toString());
            try(Playwright playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));Browser browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));Page page=browser.newPage()){
                page.setContent(html);page.pdf(new Page.PdfOptions().setPath(path).setFormat("A4").setPrintBackground(true).setDisplayHeaderFooter(true).setHeaderTemplate("<span></span>").setFooterTemplate("<div style='font-size:8px;color:#607b86;width:100%;text-align:center'>视界 · AI 网站质量综合报告 · <span class='pageNumber'></span> / <span class='totalPages'></span></div>"));
            }
        } catch(Exception ex){throw new IllegalStateException("综合报告 PDF 生成失败",ex);}
    }
    private void tableRow(StringBuilder body,String label,Object value){body.append("<tr><td>").append(escape(label)).append("</td><td>").append(escape(value)).append("</td></tr>");}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    @SuppressWarnings("unchecked") private Map<String,Object> read(String value){try{return (Map<String,Object>)json.readValue(value,Map.class);}catch(Exception ex){return Map.of();}}
    private String escape(Object value){return Objects.toString(value,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    public record Snapshot(String id,String runId,String schemaVersion,String contentHash,String payload,LocalDateTime createdAt,boolean jsonReady,boolean pdfReady){}
    public record ReportFile(Path path,String contentType,String filename){}
}
