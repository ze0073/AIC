package com.shijie.accessibility.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.mapper.*;
import com.shijie.accessibility.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class ComparisonService {
    private final ScanTaskMapper scans;
    private final FindingMapper findings;
    private final EvidenceMapper evidences;
    private final AnalysisTraceMapper traces;
    private final FixPatchMapper patches;
    private final ObjectMapper json;
    private final StorageService storage;

    public ComparisonService(ScanTaskMapper scans, FindingMapper findings, EvidenceMapper evidences,
                             AnalysisTraceMapper traces, FixPatchMapper patches, ObjectMapper json, StorageService storage) {
        this.scans=scans; this.findings=findings; this.evidences=evidences;
        this.traces=traces; this.patches=patches; this.json=json;
        this.storage=storage;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Result compare(String projectId, String beforeId, String afterId) {
        ScanTaskEntity before = required(projectId, beforeId), after = required(projectId, afterId);
        List<String> reasons = incompatibilities(before, after, json);
        List<FindingEntity> left = findings.selectList(new QueryWrapper<FindingEntity>().eq("scan_task_id", beforeId).orderByAsc("rule_id", "page_path", "selector"));
        List<FindingEntity> right = findings.selectList(new QueryWrapper<FindingEntity>().eq("scan_task_id", afterId).orderByAsc("rule_id", "page_path", "selector"));
        List<FixPatchEntity> patchList = patches.selectList(new QueryWrapper<FixPatchEntity>()
                .eq("base_version_id", before.versionId).eq("new_version_id", after.versionId).orderByAsc("created_at"));
        List<Pair> pairs = pair(left, right);
        Map<String,Map<String,String>> sourceDom=new LinkedHashMap<>();
        for(Pair pair:pairs) {
            FindingEntity target=pair.before!=null?pair.before:pair.after;
            sourceDom.put(target.id,Map.of("before",sourceDom(before,target),"after",sourceDom(after,target)));
        }
        List<String> verified = new ArrayList<>();
        for (FixPatchEntity patch : patchList) {
            var verification = json.readTree(patch.verification == null ? "{}" : patch.verification);
            if (reasons.isEmpty() && "VERIFIED".equals(patch.status)
                    && afterId.equals(verification.path("rescanId").asText())
                    && verification.path("ruleCleared").asBoolean(false)
                    && pairs.stream().anyMatch(p -> p.before != null && p.before.id.equals(patch.findingId) && p.after == null)) verified.add(patch.findingId);
        }
        Map<String,List<EvidenceEntity>> evidenceMap = new LinkedHashMap<>();
        for (FindingEntity f : concat(left,right)) evidenceMap.put(f.id, evidences.selectList(new QueryWrapper<EvidenceEntity>().eq("finding_id", f.id).orderByAsc("created_at")));
        List<AnalysisTraceEntity> traceList = traces.selectList(new QueryWrapper<AnalysisTraceEntity>().in("scan_task_id", List.of(beforeId,afterId)).orderByAsc("page_path"));
        return new Result(before, after, reasons.isEmpty(), reasons, pairs,
                reasons.isEmpty() ? count(pairs,"NOT_REDETECTED") : null,
                reasons.isEmpty() ? count(pairs,"INTRODUCED") : null,
                reasons.isEmpty() ? count(pairs,"REMAINING") : null,
                (int)pairs.stream().filter(Pair::reviewChanged).count(), verified, evidenceMap, traceList, patchList, sourceDom);
    }

    private ScanTaskEntity required(String projectId,String id) {
        ScanTaskEntity s=scans.selectById(id);
        if(s==null || !projectId.equals(s.projectId)) throw ApiException.notFound("项目中的扫描不存在");
        return s;
    }

    private String sourceDom(ScanTaskEntity scan,FindingEntity target) {
        try {
            var path=storage.resolveVersionFile(scan.projectId,scan.versionId,target.pagePath);
            var doc=org.jsoup.Jsoup.parse(java.nio.file.Files.readString(path));
            var nodes=doc.select(target.selector);
            if(nodes.size()!=1) return "源码中无法唯一定位该元素；可能由脚本生成，请结合扫描证据复核。";
            String html=nodes.first().outerHtml();
            return html.length()>4000?html.substring(0,4000)+"\n[源码摘要已截断]":html;
        } catch(Exception ex) {return "源码摘要不可用，请查看扫描证据与补丁 Diff。";}
    }

    public static List<String> incompatibilities(ScanTaskEntity a, ScanTaskEntity b,ObjectMapper json) {
        List<String> reasons=new ArrayList<>();
        if(!"COMPLETED".equals(a.status)||!"COMPLETED".equals(b.status)) reasons.add("两次扫描必须均完整完成");
        if(!Objects.equals(a.ruleVersion,b.ruleVersion)) reasons.add("规则版本不同");
        try {
            var ac=json.readTree(a.scanConfig); var bc=json.readTree(b.scanConfig);
            if(!set(ac.path("rules")).equals(set(bc.path("rules")))) reasons.add("规则集合不同");
            if(ac.path("enableKeyboardTest").asBoolean()!=bc.path("enableKeyboardTest").asBoolean()) reasons.add("键盘配置不同");
            var ap=set(ac.has("resolvedPages")?ac.path("resolvedPages"):ac.path("pages"));
            var bp=set(bc.has("resolvedPages")?bc.path("resolvedPages"):bc.path("pages"));
            if(ap.isEmpty() || bp.isEmpty()) reasons.add("历史扫描未记录可核实的页面范围，请重新扫描");
            else if(!ap.equals(bp)) reasons.add("扫描页面范围不同");
        } catch(Exception e) { reasons.add("扫描配置无法核实"); }
        return reasons;
    }
    private static Set<String> set(tools.jackson.databind.JsonNode node) {
        Set<String> values=new TreeSet<>(); node.forEach(v->values.add(v.asText())); return values;
    }
    static String key(FindingEntity f) { return f.ruleId+"|"+f.pagePath+"|"+ScanService.normalizeSelector(f.selector); }
    public static List<Pair> pair(List<FindingEntity> left,List<FindingEntity> right) {
        Map<String,FindingEntity> remaining=new LinkedHashMap<>(); right.forEach(f->remaining.put(key(f),f));
        List<Pair> result=new ArrayList<>();
        for(FindingEntity a:left) {
            FindingEntity b=remaining.remove(key(a));
            result.add(new Pair(a,b,b==null?"NOT_REDETECTED":"REMAINING",b!=null&&!Objects.equals(a.status,b.status),
                    b!=null&&!Objects.equals(a.evidenceFingerprint,b.evidenceFingerprint)));
        }
        remaining.values().forEach(b->result.add(new Pair(null,b,"INTRODUCED",false,false)));
        return result;
    }
    private static int count(List<Pair> pairs,String kind){return (int)pairs.stream().filter(p->kind.equals(p.kind)).count();}
    private static List<FindingEntity> concat(List<FindingEntity>a,List<FindingEntity>b){List<FindingEntity> r=new ArrayList<>(a);r.addAll(b);return r;}
    public record Pair(FindingEntity before,FindingEntity after,String kind,boolean reviewChanged,boolean evidenceChanged) {}
    public record Result(ScanTaskEntity before,ScanTaskEntity after,boolean comparable,List<String> reasons,List<Pair> pairs,
                         Integer notRedetected,Integer introduced,Integer remaining,int reviewChanged,List<String> verifiedFindingIds,
                         Map<String,List<EvidenceEntity>> evidences,List<AnalysisTraceEntity> traces,List<FixPatchEntity> patches,
                         Map<String,Map<String,String>> sourceDom) {}
}
