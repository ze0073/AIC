package com.shijie.accessibility.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class AgentInsightService {
    private static final Set<String> TERMINAL=Set.of("COMPLETED","PARTIAL","FAILED","CANCELLED","INTERRUPTED");
    private static final Map<String,Integer> PRIORITY_ORDER=Map.of("P0",0,"P1",1,"P2",2,"P3",3);
    private final AgentTaskService tasks;
    private final TestRunService runs;
    private final RunComparisonService comparisons;

    public AgentInsightService(AgentTaskService tasks,TestRunService runs,RunComparisonService comparisons){
        this.tasks=tasks;this.runs=runs;this.comparisons=comparisons;
    }

    public Map<String,Object> insights(String taskId){
        AgentTaskService.TaskView task=tasks.get(taskId);
        Map<String,List<TestRunService.RunView>> runCache=new HashMap<>();
        List<AgentTaskService.TaskView> history=tasks.list(task.projectId()).stream()
                .filter(item->item.targetId().equals(task.targetId())&&item.suiteVersionId().equals(task.suiteVersionId()))
                .filter(item->TERMINAL.contains(item.status())&&item.finishedAt()!=null)
                .sorted(Comparator.comparing(AgentTaskService.TaskView::finishedAt))
                .toList();
        if(TERMINAL.contains(task.status())&&history.stream().noneMatch(item->item.id().equals(task.id()))) {
            List<AgentTaskService.TaskView> copy=new ArrayList<>(history);copy.add(task);
            copy.sort(Comparator.comparing(item->Optional.ofNullable(item.finishedAt()).orElse(item.createdAt())));history=copy;
        }
        if(history.size()>8)history=history.subList(history.size()-8,history.size());
        List<Map<String,Object>> points=history.stream().map(item->trendPoint(item,taskRuns(item,runCache))).toList();
        Map<String,Object> currentPoint=points.stream().filter(point->task.id().equals(point.get("taskId"))).findFirst()
                .orElseGet(()->trendPoint(task,taskRuns(task,runCache)));
        Map<String,Object> previousPoint=previousPoint(points,task.id());
        List<IssueContext> issues=issueContexts(taskRuns(task,runCache));
        List<String> incomplete=stringList(task.summary().get("incompleteModules"));
        List<String> unexecuted=stringList(task.summary().get("unexecutedModules"));
        List<Map<String,Object>> actions=actionItems(task,issues,incomplete,unexecuted);
        List<String> limitations=limitations(task,issues,incomplete,unexecuted,previousPoint,currentPoint);
        Map<String,Object> counts=counts(issues,incomplete);
        Map<String,Object> trend=trend(points,currentPoint,previousPoint,task.status());
        String confidence=confidence(task,issues,incomplete,unexecuted);

        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schemaVersion","0.8.0");result.put("taskId",task.id());result.put("projectId",task.projectId());
        result.put("status",task.status());result.put("headline",headline(task,counts));
        result.put("summary",plainSummary(task,counts,trend));result.put("confidence",confidence);
        result.put("counts",counts);result.put("actionItems",actions);result.put("trend",trend);
        result.put("limitations",limitations);result.put("evidenceLinks",evidenceLinks(taskRuns(task,runCache)));
        result.put("generatedAt",LocalDateTime.now());return result;
    }

    private List<TestRunService.RunView> taskRuns(AgentTaskService.TaskView task,Map<String,List<TestRunService.RunView>> cache){
        return cache.computeIfAbsent(task.id(),unused->{
            LinkedHashSet<String> ids=new LinkedHashSet<>();
            task.steps().stream().filter(step->"RUN_MODULE".equals(step.action())&&step.runId()!=null).forEach(step->ids.add(step.runId()));
            List<TestRunService.RunView> values=new ArrayList<>();
            for(String id:ids)try{values.add(runs.get(id));}catch(RuntimeException ignored){/* Preserve the remaining evidence. */}
            return List.copyOf(values);
        });
    }

    private Map<String,Object> trendPoint(AgentTaskService.TaskView task,List<TestRunService.RunView> taskRuns){
        List<IssueContext> issues=issueContexts(taskRuns);
        Map<String,Object> value=new LinkedHashMap<>();value.put("taskId",task.id());value.put("status",task.status());
        value.put("goal",task.goal());value.put("finishedAt",Optional.ofNullable(task.finishedAt()).orElse(task.createdAt()));
        value.put("issueCount",actionable(issues).size());value.put("high",countSeverity(issues,"HIGH"));
        value.put("medium",countSeverity(issues,"MEDIUM"));value.put("low",countSeverity(issues,"LOW"));
        LinkedHashSet<String> completed=new LinkedHashSet<>();
        for(TestRunService.RunView run:taskRuns)for(TestRunService.ModuleView module:run.modules())if("COMPLETED".equals(module.status()))completed.add(module.moduleType());
        value.put("completedModules",List.copyOf(completed));
        taskRuns.stream().filter(run->module(run,"PERFORMANCE")!=null).findFirst().ifPresent(run->{
            Map<String,Object> metrics=comparisons.agentMetrics(run,"PERFORMANCE");
            putNumber(value,"p95Ms",metrics.get("p95Ms"));putNumber(value,"errorRate",metrics.get("errorRate"));
        });
        taskRuns.stream().filter(run->module(run,"FRONTEND")!=null).findFirst().ifPresent(run->{
            Map<String,Object> metrics=comparisons.agentMetrics(run,"FRONTEND");
            if(metrics.get("pages") instanceof Map<?,?> pages){
                List<Double> navigation=new ArrayList<>(),lcp=new ArrayList<>();
                for(Object raw:pages.values())if(raw instanceof Map<?,?> page){addNumber(navigation,page.get("navigationMs"));addNumber(lcp,page.get("lcpMs"));}
                if(!navigation.isEmpty())value.put("navigationMs",navigation.stream().mapToDouble(Double::doubleValue).average().orElse(0));
                if(!lcp.isEmpty())value.put("lcpMs",lcp.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            }
        });
        return value;
    }

    private List<IssueContext> issueContexts(List<TestRunService.RunView> taskRuns){
        List<IssueContext> result=new ArrayList<>();
        for(TestRunService.RunView run:taskRuns){
            Map<String,String> moduleById=new HashMap<>();run.modules().forEach(module->moduleById.put(module.id(),module.moduleType()));
            run.issues().forEach(issue->result.add(new IssueContext(issue,run.id(),moduleById.getOrDefault(issue.moduleId(),"UNKNOWN"))));
        }
        return result;
    }

    private List<Map<String,Object>> actionItems(AgentTaskService.TaskView task,List<IssueContext> issues,List<String> incomplete,List<String> unexecuted){
        List<Map<String,Object>> result=new ArrayList<>();
        for(IssueContext context:actionable(issues)){
            TestRunService.IssueView issue=context.issue();String priority=priority(issue);
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",issue.id());item.put("kind","ISSUE");item.put("priority",priority);
            item.put("title",issue.title());item.put("reason",issue.description());item.put("module",context.module());
            item.put("source",issue.source());item.put("issueId",issue.id());item.put("runId",context.runId());
            item.put("reviewStatus",issue.reviewStatus());item.put("evidenceConfidence",issue.evidenceConfidence());
            item.put("evidenceNote",evidenceNote(issue));item.put("recommendedOwner",owner(context.module()));
            item.put("nextStep",nextStep(context.module(),issue));item.put("prevention",prevention(context.module(),issue));
            item.put("detailPath","/projects/"+task.projectId()+"/collaboration?issueId="+issue.id());result.add(item);
        }
        LinkedHashSet<String> missing=new LinkedHashSet<>(incomplete);missing.addAll(unexecuted);
        for(String module:missing){
            Map<String,Object> item=new LinkedHashMap<>();item.put("id","retry:"+module);item.put("kind","RETRY");
            item.put("priority","P1");item.put("title","补齐"+moduleName(module)+"检查");
            item.put("reason","该模块未完整取得结果，当前不能据此判断通过或改善。");item.put("module",module);
            item.put("evidenceNote","缺少完整执行证据");item.put("recommendedOwner","测试负责人");
            item.put("nextStep","确认目标和工具可用后重新运行该模块，并保留新的运行链接。");
            item.put("prevention","把工具健康检查和目标连通性检查加入运行前准备清单。");
            item.put("detailPath","/agent-tasks/"+task.id());result.add(item);
        }
        result.sort(Comparator.comparingInt(item->PRIORITY_ORDER.getOrDefault(String.valueOf(item.get("priority")),9)));
        if(result.isEmpty()){
            Map<String,Object> item=new LinkedHashMap<>();item.put("id","maintain:"+task.id());item.put("kind","MAINTENANCE");item.put("priority","P3");
            item.put("title","把本次结果作为后续检查基线");item.put("reason","本次没有形成需要立即处理的行动项。");item.put("module","ALL");
            item.put("evidenceNote","本次已保存运行和证据链接");item.put("recommendedOwner","项目负责人");
            item.put("nextStep","下次使用同一网站和方案检查时选择本任务作为历史基线。");
            item.put("prevention","保持方案版本和检查范围一致，变化时建立新的基线。");item.put("detailPath","/agent-tasks/"+task.id());result.add(item);
        }
        return result.size()>8?List.copyOf(result.subList(0,8)):List.copyOf(result);
    }

    private List<String> limitations(AgentTaskService.TaskView task,List<IssueContext> issues,List<String> incomplete,List<String> unexecuted,
                                     Map<String,Object> previous,Map<String,Object> current){
        LinkedHashSet<String> values=new LinkedHashSet<>();
        if(!incomplete.isEmpty())values.add("未完整完成："+joinModules(incomplete)+"；这些模块不能判定为通过。");
        if(!unexecuted.isEmpty())values.add("未执行："+joinModules(unexecuted)+"；结果只覆盖实际运行的模块。");
        long uncertain=issues.stream().filter(item->Set.of("INCONCLUSIVE","CONFIGURATION").contains(item.issue().evidenceConfidence())).count();
        if(uncertain>0)values.add("有 "+uncertain+" 项证据不足或属于配置提示，需要结合运行条件判断。");
        if(previous.isEmpty())values.add("暂无同网站、同方案的历史智能检查，趋势只展示本次结果。");
        if(task.plan().get("modules") instanceof List<?> modules&&modules.contains("PERFORMANCE")&&!current.containsKey("p95Ms"))
            values.add("未取得有效性能耗时，不能把 0 或缺失值解释为性能正常。");
        if(!"COMPLETED".equals(task.status()))values.add("本次任务状态为 "+task.status()+"，趋势变化仅供定位，不能作为改善结论。");
        values.add("自动候选不会被系统确认为漏洞；需要结论时请打开证据并由项目成员审阅。");
        return List.copyOf(values);
    }

    private Map<String,Object> counts(List<IssueContext> issues,List<String> incomplete){
        List<IssueContext> actionable=actionable(issues);Map<String,Object> result=new LinkedHashMap<>();
        result.put("issues",actionable.size());result.put("high",countSeverity(issues,"HIGH"));
        result.put("medium",countSeverity(issues,"MEDIUM"));result.put("low",countSeverity(issues,"LOW"));
        result.put("pendingReview",actionable.stream().filter(item->"PENDING_REVIEW".equals(item.issue().reviewStatus())).count());
        result.put("incompleteModules",incomplete.size());return result;
    }

    private Map<String,Object> trend(List<Map<String,Object>> points,Map<String,Object> current,Map<String,Object> previous,String status){
        Map<String,Object> assessment=new LinkedHashMap<>();
        if(previous.isEmpty()){assessment.put("overall","NO_BASELINE");assessment.put("issues","NO_DATA");assessment.put("performance","NO_DATA");assessment.put("experience","NO_DATA");assessment.put("message","这是首个可比较的智能检查，后续可用它观察变化。");}
        else if(!"COMPLETED".equals(status)){assessment.put("overall","LIMITED");assessment.put("issues","NO_DATA");assessment.put("performance","NO_DATA");assessment.put("experience","NO_DATA");assessment.put("message","本次任务未完整结束，暂不把数值变化解释为改善或退化。");}
        else {
            String issues=direction(current.get("issueCount"),previous.get("issueCount"),0);
            String performance=direction(current.get("p95Ms"),previous.get("p95Ms"),.05);
            String experience=direction(current.get("navigationMs"),previous.get("navigationMs"),.05);
            String overall=List.of(issues,performance,experience).contains("WORSE")?"WORSE":List.of(issues,performance,experience).contains("IMPROVED")?"IMPROVED":
                    List.of(issues,performance,experience).stream().allMatch("NO_DATA"::equals)?"NO_DATA":"STABLE";
            assessment.put("overall",overall);assessment.put("issues",issues);assessment.put("performance",performance);assessment.put("experience",experience);
            assessment.put("message",switch(overall){case "IMPROVED"->"与上一次同范围检查相比，至少一项核心指标改善，且没有观察到明确退化。";case "WORSE"->"与上一次同范围检查相比，至少一项核心指标退化，建议优先查看行动清单和证据。";case "STABLE"->"与上一次同范围检查相比，核心指标整体稳定。";default->"两次检查缺少足够的同类指标，暂时不能判断趋势。";});
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("points",points);result.put("assessment",assessment);return result;
    }

    private String headline(AgentTaskService.TaskView task,Map<String,Object> counts){
        int issues=number(counts.get("issues")),high=number(counts.get("high")),missing=number(counts.get("incompleteModules"));
        if(!TERMINAL.contains(task.status()))return "智能检查正在进行";
        if(high>0)return "发现 "+high+" 个需要优先处理的问题";
        if(issues>0)return "发现 "+issues+" 个可以按顺序改进的事项";
        if(missing>0)return "检查已结束，但仍有模块需要补齐";
        return "本次检查没有发现需要立即处理的问题";
    }

    @SuppressWarnings("unchecked")
    private String plainSummary(AgentTaskService.TaskView task,Map<String,Object> counts,Map<String,Object> trend){
        int issues=number(counts.get("issues")),pending=number(counts.get("pendingReview"));
        Map<String,Object> assessment=trend.get("assessment") instanceof Map<?,?> raw?(Map<String,Object>)raw:Map.of();
        String base="本次在已确认范围内完成检查，整理出 "+issues+" 个行动项来源问题"+(pending>0?"，其中 "+pending+" 个仍是待审阅候选":"")+"。";
        return base+Objects.toString(assessment.get("message"),"");
    }

    private String confidence(AgentTaskService.TaskView task,List<IssueContext> issues,List<String> incomplete,List<String> unexecuted){
        if("COMPLETED".equals(task.status())&&incomplete.isEmpty()&&unexecuted.isEmpty()&&issues.stream().noneMatch(item->Set.of("INCONCLUSIVE","CONFIGURATION").contains(item.issue().evidenceConfidence())))return "HIGH";
        if(TERMINAL.contains(task.status())&&!taskRunsEmpty(task))return "MEDIUM";return "LIMITED";
    }

    private boolean taskRunsEmpty(AgentTaskService.TaskView task){return task.steps().stream().noneMatch(step->"RUN_MODULE".equals(step.action())&&step.runId()!=null);}
    private List<Map<String,Object>> evidenceLinks(List<TestRunService.RunView> taskRuns){
        List<Map<String,Object>> links=new ArrayList<>();
        for(TestRunService.RunView run:taskRuns)for(TestRunService.ModuleView module:run.modules()){
            Map<String,Object> link=new LinkedHashMap<>();link.put("runId",run.id());link.put("module",module.moduleType());link.put("status",module.status());
            link.put("label",moduleName(module.moduleType())+"证据");link.put("detailPath","/test-runs/"+run.id());links.add(link);
        }return links;
    }

    private Map<String,Object> previousPoint(List<Map<String,Object>> points,String currentTaskId){
        for(int index=points.size()-1;index>=0;index--)if(currentTaskId.equals(points.get(index).get("taskId")))return index>0?points.get(index-1):Map.of();
        return points.isEmpty()?Map.of():points.getLast();
    }

    private List<IssueContext> actionable(List<IssueContext> issues){return issues.stream().filter(item->!"CONFIGURATION".equals(item.issue().evidenceConfidence())).toList();}
    private long countSeverity(List<IssueContext> issues,String severity){return actionable(issues).stream().filter(item->severity.equals(item.issue().severity())).count();}
    private TestRunService.ModuleView module(TestRunService.RunView run,String type){return run.modules().stream().filter(item->type.equals(item.moduleType())).findFirst().orElse(null);}
    private String priority(TestRunService.IssueView issue){if("HIGH".equals(issue.severity()))return "P0";if("MEDIUM".equals(issue.severity())||"INCONCLUSIVE".equals(issue.evidenceConfidence()))return "P1";return "P2";}
    private String evidenceNote(TestRunService.IssueView issue){return switch(issue.evidenceConfidence()){case "HIGH"->"高可信自动证据，仍需结合业务确认结论";case "MEDIUM"->"证据可信度中等，建议先复现再处理";case "INCONCLUSIVE"->"证据不足，当前不能确认问题";default->"自动检测候选，尚未由成员确认";};}
    private String owner(String module){return switch(module){case "FRONTEND"->"前端负责人";case "SECURITY"->"后端或安全负责人";case "PERFORMANCE"->"性能或运维负责人";default->"项目负责人";};}
    private String nextStep(String module,TestRunService.IssueView issue){return switch(module){case "FRONTEND"->"打开页面证据定位元素，完成最小修改后用同一方案复测。";case "SECURITY"->"按保存的请求身份和参数复现，核对权限、输入校验及响应差异。";case "PERFORMANCE"->"先确认有效样本和失败率，再定位慢请求或资源瓶颈并复测。";default->"查看原始证据和运行条件，确认后再进入修复。";};}
    private String prevention(String module,TestRunService.IssueView issue){return switch(module){case "FRONTEND"->"把对应页面和规则加入提交前检查，并在公共组件中固化可访问性约束。";case "SECURITY"->"把该权限或输入边界加入只读回归用例，发布前固定使用不同身份验证。";case "PERFORMANCE"->"保留当前阈值与负载范围，在发布前用同一配置运行趋势回归。";default->"保存本次证据为基线，并把复现步骤加入团队检查清单。";};}
    private String moduleName(String module){return switch(module){case "FRONTEND"->"页面体验";case "SECURITY"->"接口与权限";case "PERFORMANCE"->"承载能力";default->module;};}
    private String joinModules(List<String> modules){return modules.stream().map(this::moduleName).distinct().reduce((a,b)->a+"、"+b).orElse("无");}
    private String direction(Object current,Object previous,double tolerance){if(!(current instanceof Number now)||!(previous instanceof Number before))return "NO_DATA";double a=now.doubleValue(),b=before.doubleValue();if(!Double.isFinite(a)||!Double.isFinite(b))return "NO_DATA";double margin=Math.max(Math.abs(b)*tolerance,tolerance==0?0:1);if(a<b-margin)return "IMPROVED";if(a>b+margin)return "WORSE";return "STABLE";}
    private void putNumber(Map<String,Object> target,String key,Object value){if(value instanceof Number number&&Double.isFinite(number.doubleValue()))target.put(key,number.doubleValue());}
    private void addNumber(List<Double> values,Object value){if(value instanceof Number number&&Double.isFinite(number.doubleValue()))values.add(number.doubleValue());}
    private int number(Object value){return value instanceof Number number?number.intValue():0;}
    private List<String> stringList(Object value){return value instanceof List<?> list?list.stream().map(String::valueOf).filter(item->!item.isBlank()).toList():List.of();}
    private record IssueContext(TestRunService.IssueView issue,String runId,String module){}
}
