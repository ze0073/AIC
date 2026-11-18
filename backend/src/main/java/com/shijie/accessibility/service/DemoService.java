package com.shijie.accessibility.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.mapper.*;
import com.shijie.accessibility.model.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class DemoService {
    private final ProjectService projects;
    private final ScanTaskMapper scans;
    private final FindingMapper findings;
    private final FindingReviewMapper reviews;
    private final FixPatchMapper patches;
    private final EvidenceMapper evidences;
    private final AnalysisTraceMapper traces;
    private final ReportSnapshotService reports;
    private final ObjectMapper json;
    public DemoService(ProjectService projects,ScanTaskMapper scans,FindingMapper findings,FindingReviewMapper reviews,
            FixPatchMapper patches,EvidenceMapper evidences,AnalysisTraceMapper traces,ReportSnapshotService reports,ObjectMapper json) {
        this.projects=projects;this.scans=scans;this.findings=findings;this.reviews=reviews;this.patches=patches;
        this.evidences=evidences;this.traces=traces;this.reports=reports;this.json=json;
    }
    public State state(String projectId,String versionId,String scanId) {
        var project=projects.get(projectId);
        ScanTaskEntity scan=scanId==null?null:scans.selectById(scanId);
        if(scanId!=null&&(scan==null||!projectId.equals(scan.projectId))) throw ApiException.notFound("项目中的扫描不存在");
        String selected=versionId!=null?versionId:scan!=null?scan.versionId:project.currentVersionId;
        ProjectVersionEntity version=selected==null?null:projects.version(selected);
        if(version!=null&&!projectId.equals(version.projectId)) throw ApiException.notFound("项目中的版本不存在");
        if(scan!=null&&!Objects.equals(selected,scan.versionId)) throw ApiException.badRequest("DEMO_CONTEXT_MISMATCH","版本与扫描不匹配");
        if(scan==null&&selected!=null) {
            var candidates=scans.selectList(new QueryWrapper<ScanTaskEntity>().eq("version_id",selected).orderByDesc("created_at").last("LIMIT 1"));
            if(!candidates.isEmpty()) scan=candidates.getFirst();
        }
        var items=scan==null?List.<FindingEntity>of():findings.selectList(new QueryWrapper<FindingEntity>().eq("scan_task_id",scan.id).orderByAsc("rule_id"));
        var ids=items.stream().map(f->f.id).toList();
        var reviewList=ids.isEmpty()?List.<FindingReviewEntity>of():reviews.selectList(new QueryWrapper<FindingReviewEntity>().in("finding_id",ids));
        var patchList=ids.isEmpty()?List.<FixPatchEntity>of():patches.selectList(new QueryWrapper<FixPatchEntity>().in("finding_id",ids).orderByDesc("created_at"));
        var snapshots=scan==null?List.<ReportSnapshotService.Summary>of():reports.list(scan.id);
        boolean ready=scan!=null&&List.of("COMPLETED","PARTIAL").contains(scan.status);
        boolean hasEvidence=scan!=null&&(!ids.isEmpty()&&evidences.selectCount(new QueryWrapper<EvidenceEntity>().in("finding_id",ids))>0
                ||traces.selectCount(new QueryWrapper<AnalysisTraceEntity>().eq("scan_task_id",scan.id))>0);
        String reviewFinding=items.stream().filter(f->"R009".equals(f.ruleId)).map(f->f.id).findFirst().orElse(null);
        String fixFinding=items.stream().filter(f->"TEMPLATE_PATCH".equals(f.fixMode)).sorted(Comparator.comparing(f->!"R004".equals(f.ruleId))).map(f->f.id).findFirst().orElse(null);
        String before=null,after=null;
        for(var patch:patchList) {
            var v=json.readTree(patch.verification==null?"{}":patch.verification);
            String retest=v.path("rescanId").asText("");
            if(!retest.isBlank()) {before=scan.id;after=retest;break;}
        }
        if (before==null && version!=null) {
            var incoming=patches.selectList(new QueryWrapper<FixPatchEntity>().eq("new_version_id",version.id).orderByDesc("created_at"));
            for(var patch:incoming) {
                String retest=json.readTree(patch.verification==null?"{}":patch.verification).path("rescanId").asText("");
                var original=findings.selectById(patch.findingId);
                if(!retest.isBlank()&&original!=null) {before=original.scanTaskId;after=retest;break;}
            }
        }
        List<Step> steps=List.of(new Step("import","导入",version!=null?"DONE":"PENDING"),
                new Step("scan","扫描",ready?"DONE":"PENDING"),
                new Step("evidence","查看证据",hasEvidence?"READY":"PENDING"),
                new Step("review","可选审阅",!reviewList.isEmpty()?"DONE":ready&&reviewFinding!=null?"READY":"NOT_APPLICABLE"),
                new Step("fix","修复复测",patchList.stream().anyMatch(p->"VERIFIED".equals(p.status))?"DONE":ready&&fixFinding==null?"NOT_APPLICABLE":"PENDING"),
                new Step("report","成果导出",snapshots.stream().anyMatch(s->s.pdfReady()&&s.jsonReady())?"DONE":!snapshots.isEmpty()?"READY":"PENDING"));
        return new State(project,version,scan,steps,reviewFinding,fixFinding,patchList,snapshots,before,after);
    }
    public record Step(String key,String title,String status) {}
    public record State(ProjectEntity project,ProjectVersionEntity version,ScanTaskEntity scan,List<Step> steps,
                        String reviewFindingId,String fixFindingId,List<FixPatchEntity> patches,
                        List<ReportSnapshotService.Summary> snapshots,String beforeScanId,String afterScanId) {}
}
