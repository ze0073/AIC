package com.shijie.accessibility.service;

import com.shijie.accessibility.ai.AiProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Service
public class IssueExplanationService {
    private final CollaborationService collaboration;private final JdbcTemplate jdbc;private final ObjectMapper json;private final AiProvider provider;
    public IssueExplanationService(CollaborationService collaboration,JdbcTemplate jdbc,ObjectMapper json,AiProvider provider){this.collaboration=collaboration;this.jdbc=jdbc;this.json=json;this.provider=provider;}
    public Explanation explain(String issueId){return explain(issueId,true);}
    public Explanation explain(String issueId,boolean useAi){Map<String,Object>issue=collaboration.issue(issueId);
        if(!useAi)return fallback(issue,"已选择快速规则解释");
        Map<String,Object>evidence=sanitize(issue.get("evidence"));List<Map<String,Object>>cases=jdbc.query("SELECT id,title,category,content FROM knowledge_cases WHERE project_id=? AND status='PUBLISHED' ORDER BY updated_at DESC LIMIT 3",(rs,n)->{Map<String,Object>m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("title",rs.getString("title"));m.put("category",rs.getString("category"));m.put("content",sanitize(read(rs.getString("content"))));return m;},String.valueOf(issue.get("projectId")));
        if(provider.enabled()){try{var result=provider.explainIssue(new AiProvider.IssueRequest(String.valueOf(issue.get("source")),String.valueOf(issue.get("title")),limit(String.valueOf(issue.get("description")),1000),evidence,cases));if(result.isPresent())return new Explanation("AI",provider.provider(),provider.model(),result.get().phenomenon(),result.get().possibleCauses(),result.get().evidenceBasis(),result.get().fixSuggestions(),result.get().verificationSteps(),true,null);}catch(Exception ex){return fallback(issue,"AI 服务失败："+concise(ex));}}
        return fallback(issue,provider.unavailableReason());
    }
    private Explanation fallback(Map<String,Object>issue,String reason){String source=String.valueOf(issue.get("source"));List<String>checks=new ArrayList<>();checks.add("按保存的目标、身份和参数重新执行该用例");checks.add("对照修复前后的状态码、关键字段和响应头");if("ZAP".equals(source))checks.add("可由成员按需审阅脱敏请求响应，判断引擎候选是否可复现");return new Explanation("RULE",null,null,String.valueOf(issue.get("title")),List.of("检查输入校验、权限边界、部署配置或资源依赖是否符合用例前提"),List.of(String.valueOf(issue.get("description"))),List.of("依据证据定位最小修复范围，避免把配置风险直接当作已确认漏洞"),checks,true,reason);}
    @SuppressWarnings("unchecked")private Map<String,Object>sanitize(Object value){Map<String,Object>input=value instanceof Map<?,?>map?(Map<String,Object>)map:Map.of();String serialized=limit(write(input).replaceAll("(?i)(authorization|cookie|token|secret|password)\\s*[:=]\\s*[^\\s,;\"}]+","$1=<redacted>"),8000);Object parsed=read(serialized);return parsed instanceof Map<?,?>map?(Map<String,Object>)map:Map.of("summary",serialized);}
    private Object read(String value){try{return json.readValue(value,Object.class);}catch(Exception ex){return Map.of();}}private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){return "{}";}}private String limit(String value,int max){return value.length()<=max?value:value.substring(0,max);}private String concise(Exception ex){return limit(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage(),300);}
    public record Explanation(String mode,String provider,String model,String phenomenon,List<String>possibleCauses,List<String>evidenceBasis,List<String>fixSuggestions,List<String>verificationSteps,boolean reviewRequired,String unavailableReason){}
}
