package com.shijie.accessibility.scanner;

import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.online.TargetScopeService;
import com.shijie.accessibility.online.PinnedHttp;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

@Component
public class ApiSecurityChecker {
    private final TargetScopeService scope;
    private final ObjectMapper json;
    private final PinnedHttp http;

    public ApiSecurityChecker(TargetScopeService scope, ObjectMapper json,PinnedHttp http) { this.scope=scope; this.json=json;this.http=http; }

    public Output check(OnlineTargetService.TargetView target, List<Map<String,Object>> cases,
                        Function<String,OnlineTargetService.SecretCredential> credentials) {
        return check(target,cases,credentials,()->false);
    }

    public Output check(OnlineTargetService.TargetView target, List<Map<String,Object>> cases,
                        Function<String,OnlineTargetService.SecretCredential> credentials,BooleanSupplier cancelled) {
        List<OnlineFrontendScanner.IssueDraft> issues=new ArrayList<>();
        List<Map<String,Object>> results=new ArrayList<>();
        for(Map<String,Object> item:cases.stream().limit(10).toList()) {
            if(cancelled.getAsBoolean())break;
            String name=Objects.toString(item.getOrDefault("name","接口权限用例"));
            String kind=Objects.toString(item.getOrDefault("kind","ASSERTION")).toUpperCase();
            String method=Objects.toString(item.getOrDefault("method","GET")).toUpperCase();
            URI uri=scope.requireAllowed(target,URI.create(target.baseUrl()).resolve(Objects.toString(item.getOrDefault("path","/"))).toString());
            Map<String,Object> result=new LinkedHashMap<>(); result.put("name",name);result.put("kind",kind);result.put("method",method);result.put("url",redactUrl(uri));
            try {
                if ("UPLOAD_SECURITY".equals(kind)) {
                    UploadResult upload=checkUpload(target,item,credentials,uri);
                    result.putAll(upload.evidence()); issues.addAll(upload.issues()); results.add(result); continue;
                }
                Map<String,String> headers=new LinkedHashMap<>();headers.put("Accept","application/json,text/html;q=0.8");headers.put("User-Agent","Shijie-API-Security-Checker/0.6.1");
                String credentialId=Objects.toString(item.get("credentialId"),"");
                if(!credentialId.isBlank()) applyCredential(headers,credentials.apply(credentialId),target);
                String body=Objects.toString(item.get("body"),"");
                PinnedHttp.Result response=sendWithScopedRedirects(uri,method,headers,body.isEmpty()?null:body.getBytes(java.nio.charset.StandardCharsets.UTF_8),target,cancelled);
                String responseBody=response.body()==null?"":response.body();
                result.put("status",response.statusCode()); result.put("contentType",response.firstHeader("content-type").orElse(""));
                List<String> protectedFields=stringList(item.get("protectedFields"));
                Map<String,Object> parsed=parseObject(responseBody);
                List<String> presentFields=protectedFields.stream().filter(field->hasPath(parsed,field)).toList();
                result.put("protectedFieldsPresent",presentFields);
                List<Integer> expected=intList(item.get("expectedStatuses"));
                boolean statusMatches=expected.isEmpty()||expected.contains(response.statusCode());
                boolean authenticated=!credentialId.isBlank();
                if(authenticated&&List.of(401,403).contains(response.statusCode())&&Boolean.TRUE.equals(item.get("baseline"))) {
                    issues.add(inconclusive("CREDENTIAL_BASELINE_FAILED",name,"凭据基准检查未通过，相关权限结论无法判断。",result));
                } else if("UNAUTHENTICATED_ACCESS".equals(kind)) {
                    boolean exposed=response.statusCode()<400&&!presentFields.isEmpty();
                    if(exposed)issues.add(issue("UNAUTHENTICATED_ACCESS",name,"匿名响应包含登记的受保护字段。",result));
                    else if(!statusMatches)issues.add(inconclusive("AUTH_RESPONSE_UNEXPECTED",name,"响应状态与用例断言不一致，但未发现受保护字段。",result));
                } else if(!statusMatches) {
                    String key="CROSS_USER_ACCESS".equals(kind)?"CROSS_USER_ACCESS":"ROLE_AUTHORIZATION";
                    if(authenticated&&response.statusCode()==401)issues.add(inconclusive("CREDENTIAL_EXPIRED",name,"认证凭据无效，不能据此判断权限行为。",result));
                    else if(!presentFields.isEmpty()&&"CROSS_USER_ACCESS".equals(kind)){
                        String ownerField=Objects.toString(item.get("ownerField"),"owner");
                        String expectedOwner=Objects.toString(item.get("expectedOwner"),"");
                        Object actualOwner=valueAt(parsed,ownerField);
                        result.put("ownerField",ownerField);
                        result.put("ownerMatched",!expectedOwner.isBlank()&&expectedOwner.equals(Objects.toString(actualOwner,"")));
                        if(!expectedOwner.isBlank()&&expectedOwner.equals(Objects.toString(actualOwner,"")))issues.add(issue(key,name,"普通用户读取了明确归属其他用户的受保护资源。",result));
                        else issues.add(inconclusive("OWNER_ASSERTION_UNMET",name,"未能确认响应资源属于登记的其他用户。",result));
                    } else if(!presentFields.isEmpty())issues.add(issue(key,name,"响应包含登记的受保护字段，且不符合权限断言。",result));
                    else issues.add(inconclusive("AUTH_RESPONSE_UNEXPECTED",name,"状态不符，但响应未包含登记的受保护字段（可能是登录页或错误页）。",result));
                }
            } catch(Exception ex) {
                if(cancelled.getAsBoolean())break;
                result.put("error",concise(ex));
                issues.add(inconclusive("API_CHECK_INCONCLUSIVE",name,"网络、凭据或响应异常导致无法判断。",result));
            }
            results.add(result);
        }
        return new Output(results,issues);
    }

    private PinnedHttp.Result sendWithScopedRedirects(URI first,String method,Map<String,String> headers,byte[] body,
                                                       OnlineTargetService.TargetView target,BooleanSupplier cancelled)throws Exception{
        URI current=first;
        for(int redirects=0;redirects<=5;redirects++){
            if(cancelled.getAsBoolean())throw new IllegalStateException("接口检查已取消");
            PinnedHttp.Result response=http.send(target,current,method,headers,body,12);
            if(response.statusCode()<300||response.statusCode()>=400||response.firstHeader("location").isEmpty())return response;
            current=scope.requireAllowed(target,current.resolve(response.firstHeader("location").orElseThrow()).toString());
        }
        throw new IllegalStateException("重定向次数超过 5 次");
    }

    private void applyCredential(Map<String,String> headers,OnlineTargetService.SecretCredential credential,OnlineTargetService.TargetView target){
        if(!credential.targetId().equals(target.id())||!credential.allowedOrigin().equals(target.allowedOrigin()))throw new IllegalArgumentException("凭据与目标 Origin 不匹配");
        if("BEARER".equals(credential.authType()))headers.put("Authorization",credential.secret().startsWith("Bearer ")?credential.secret():"Bearer "+credential.secret());
        else if("COOKIE".equals(credential.authType()))headers.put("Cookie",credential.secret());
    }

    private UploadResult checkUpload(OnlineTargetService.TargetView target,Map<String,Object> item,
                                     Function<String,OnlineTargetService.SecretCredential> credentials,URI uri)throws Exception{
        String boundary="----ShijieBoundary"+UUID.randomUUID().toString().replace("-","");
        String marker="SHIJIE-HARMLESS-"+UUID.randomUUID();
        String field=Objects.toString(item.getOrDefault("fileField","file"));
        byte[] payload=("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field+"\"; filename=\"quality-check.jpg\"\r\n"+
                "Content-Type: image/jpeg\r\n\r\n"+marker+"\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String,String> headers=new LinkedHashMap<>();headers.put("Content-Type","multipart/form-data; boundary="+boundary);headers.put("User-Agent","Shijie-Upload-Security-Checker/0.6.1");
        String credentialId=Objects.toString(item.get("credentialId"),"");
        if(!credentialId.isBlank())applyCredential(headers,credentials.apply(credentialId),target);
        PinnedHttp.Result response=http.send(target,uri,"POST",headers,payload,15);
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("status",response.statusCode());evidence.put("marker",marker);evidence.put("filename","quality-check.jpg");evidence.put("declaredMime","image/jpeg");evidence.put("actualContent","plain harmless marker");
        List<OnlineFrontendScanner.IssueDraft> found=new ArrayList<>();
        Map<String,Object> responseJson=parseObject(response.body()==null?"":response.body());
        String urlField=Objects.toString(item.getOrDefault("downloadUrlField","url"));Object downloadValue=responseJson.get(urlField);
        boolean dangerousResponse=false;
        if(downloadValue!=null){URI download=scope.requireAllowed(target,uri.resolve(String.valueOf(downloadValue)).toString());PinnedHttp.Result downloadResponse=http.send(target,download,"GET",headers,null,10);String contentType=downloadResponse.firstHeader("content-type").orElse("");evidence.put("download",Map.of("url",safePath(download),"status",downloadResponse.statusCode(),"contentType",contentType));dangerousResponse=downloadResponse.statusCode()<400&&contentType.toLowerCase(Locale.ROOT).contains("text/html");}
        String cleanupPath=Objects.toString(item.get("cleanupPath"),"");
        if(response.statusCode()>=400) evidence.put("cleanup",Map.of("status","NOT_REQUIRED"));
        else if(!cleanupPath.isBlank()){try{
            String uploadId=Objects.toString(responseJson.get("id"),"");
            if(cleanupPath.contains("{id}")&&uploadId.isBlank())throw new IllegalStateException("上传响应缺少清理 ID");
            URI cleanup=scope.requireAllowed(target,URI.create(target.baseUrl()).resolve(cleanupPath.replace("{marker}",marker).replace("{id}",uploadId)).toString());
            Map<String,String> cleanupHeaders=new LinkedHashMap<>();if(!credentialId.isBlank())applyCredential(cleanupHeaders,credentials.apply(credentialId),target);
            PinnedHttp.Result deleted=http.send(target,cleanup,"DELETE",cleanupHeaders,null,10);
            evidence.put("cleanup",Map.of("status",deleted.statusCode(),"path",cleanup.getPath()));
        }catch(Exception ex){evidence.put("cleanup",Map.of("status","FAILED","error",concise(ex)));}}
        else evidence.put("cleanup",Map.of("status","MANUAL_REQUIRED","marker",marker));
        boolean accepted=response.statusCode()<400&&(responseJson.containsKey("id")||downloadValue!=null);
        if(accepted)found.add(new OnlineFrontendScanner.IssueDraft("UPLOAD_MISMATCH_ACCEPTED","UPLOAD","MEDIUM","REPRODUCIBLE_ASSERTION","上传入口接受扩展名、MIME 与内容不一致的文件","该现象只表示上传校验不足，不代表文件可执行或服务器已被入侵。",new LinkedHashMap<>(evidence)));
        else if(response.statusCode()<400)found.add(new OnlineFrontendScanner.IssueDraft("UPLOAD_RESPONSE_INCONCLUSIVE","UPLOAD","LOW","INCONCLUSIVE","上传响应不能确认已接收文件","成功状态没有上传标识或下载地址，可能是登录页或业务提示。",new LinkedHashMap<>(evidence)));
        if(dangerousResponse)found.add(new OnlineFrontendScanner.IssueDraft("UPLOAD_DANGEROUS_RESPONSE","UPLOAD","HIGH","REPRODUCIBLE_ASSERTION","上传文件以危险响应类型公开返回","无害标记文件可公开读取且以 HTML 类型响应；仍不据此宣称远程代码执行。",new LinkedHashMap<>(evidence)));
        return new UploadResult(evidence,found);
    }

    @SuppressWarnings("unchecked") private Map<String,Object> parseObject(String body){try{Object value=json.readValue(body.length()>200_000?body.substring(0,200_000):body,Object.class);return value instanceof Map<?,?> map?(Map<String,Object>)map:Map.of();}catch(Exception ex){return Map.of();}}
    private boolean hasPath(Map<String,Object> map,String path){return valueAt(map,path)!=null;}
    private Object valueAt(Map<String,Object> map,String path){Object current=map;for(String part:path.split("\\.")){if(!(current instanceof Map<?,?>m)||!m.containsKey(part))return null;current=m.get(part);}return current;}
    private List<String> stringList(Object value){return value instanceof List<?>list?list.stream().map(String::valueOf).toList():List.of();}
    private List<Integer> intList(Object value){return value instanceof List<?>list?list.stream().filter(Number.class::isInstance).map(Number.class::cast).map(Number::intValue).toList():List.of();}
    private OnlineFrontendScanner.IssueDraft issue(String key,String title,String description,Map<String,Object> evidence){return new OnlineFrontendScanner.IssueDraft(key,"API_ASSERTION","HIGH","REPRODUCIBLE_ASSERTION",title,description,new LinkedHashMap<>(evidence));}
    private OnlineFrontendScanner.IssueDraft inconclusive(String key,String title,String description,Map<String,Object> evidence){return new OnlineFrontendScanner.IssueDraft(key,"API_ASSERTION","LOW","INCONCLUSIVE",title,description,new LinkedHashMap<>(evidence));}
    private String concise(Exception ex){String value=ex.getMessage();return value==null?ex.getClass().getSimpleName():value.lines().findFirst().orElse(value);}
    String redactUrl(URI uri){
        String base=safePath(uri);
        if(uri.getRawQuery()==null)return base;
        List<String>parts=new ArrayList<>();
        for(String pair:uri.getRawQuery().split("&",-1)){
            String[]field=pair.split("=",2);
            parts.add(field.length==2&&field[0].toLowerCase(Locale.ROOT).matches(".*(token|secret|password|cookie|authorization|api.?key).*")
                    ?field[0]+"=<redacted>":pair);
        }
        return base+"?"+String.join("&",parts);
    }
    private String safePath(URI uri){return uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort())+uri.getPath();}
    private record UploadResult(Map<String,Object> evidence,List<OnlineFrontendScanner.IssueDraft> issues){}
    public record Output(List<Map<String,Object>> results,List<OnlineFrontendScanner.IssueDraft> issues){}
}
