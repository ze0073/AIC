package com.shijie.accessibility.api;

import com.shijie.accessibility.security.AuthService;
import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.hosting.*;
import com.shijie.accessibility.ai.QwenGuideClient;
import com.shijie.accessibility.common.ApiException;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.nio.file.*;
import java.time.*;
import com.microsoft.playwright.Playwright;

@RestController @RequestMapping("/api/admin/system")
public class SystemDiagnosticsController {
    private final AuthService auth;private final JdbcTemplate jdbc;private final AppProperties properties;private final HostingService hosting;private final HostingCommands commands;private final QwenGuideClient model;
    @Value("${app.tools.zap-image}") private String zapImage;
    @Value("${app.tools.k6-image}") private String k6Image;
    @Value("${app.hosting.gateway-bind-host:127.0.0.1}") private String gatewayBind;
    private volatile Map<String,Object> lastModelCheck=Map.of("status","NOT_VERIFIED","message","尚未进行真实调用验证");
    public SystemDiagnosticsController(AuthService auth,JdbcTemplate jdbc,AppProperties properties,HostingService hosting,HostingCommands commands,QwenGuideClient model){this.auth=auth;this.jdbc=jdbc;this.properties=properties;this.hosting=hosting;this.commands=commands;this.model=model;}
    @GetMapping("/status") public Map<String,Object> status(){
        auth.requirePlatformAdmin();var checks=new ArrayList<Map<String,Object>>();
        try{checks.add(check("database","平台数据库",jdbc.queryForObject("SELECT 1",Integer.class)==1,"数据库连接正常"));}catch(Exception ex){checks.add(check("database","平台数据库",false,"数据库连接失败"));}
        Path root=properties.storageRoot().toAbsolutePath().normalize();try{long free=Files.getFileStore(root).getUsableSpace();checks.add(check("storage","受控存储",Files.isWritable(root)&&free>=100L*1024*1024,"可用空间 "+free/1024/1024+" MB"));}catch(Exception ex){checks.add(check("storage","受控存储",false,"存储不可访问"));}
        try(var browser=Playwright.create()){checks.add(check("chromium","扫描浏览器",Files.isRegularFile(Path.of(browser.chromium().executablePath())),"浏览器安装检查"));}catch(Exception ex){checks.add(check("chromium","扫描浏览器",false,"请运行启动脚本安装 Chromium"));}
        var runtime=hosting.environment();checks.add(check("docker","Docker 运行引擎",runtime.get("engine")!=null, runtime.get("engine")!=null?"引擎 "+runtime.get("engine"):"请启动 Docker Desktop"));checks.add(check("worker","部署执行器",Boolean.TRUE.equals(runtime.get("workerReady")),"检查独立执行器心跳"));
        checks.add(image("zap","主动安全工具",zapImage));checks.add(image("k6","负载测试工具",k6Image));
        var result=new LinkedHashMap<String,Object>();result.put("checks",checks);result.put("gatewayBindHost",gatewayBind);result.put("gatewayMessage",runtime.getOrDefault("message","网站入口可用"));result.put("regressionTimeZone","Asia/Shanghai");result.put("assessedAt",OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
        result.put("model",Map.of("configured",model.available(),"provider",Objects.toString(properties.ai().provider(),""),"model",Objects.toString(properties.ai().model(),""),"verification",lastModelCheck));
        result.put("queue",Map.of("scans",jdbc.queryForObject("SELECT COUNT(*) FROM scan_tasks WHERE status IN ('PENDING','RUNNING')",Integer.class),"runs",jdbc.queryForObject("SELECT COUNT(*) FROM test_runs WHERE status IN ('PENDING','RUNNING')",Integer.class),"agents",jdbc.queryForObject("SELECT COUNT(*) FROM agent_tasks WHERE status='RUNNING'",Integer.class)));return result;
    }
    @PostMapping("/model-check") public synchronized Map<String,Object> checkModel(@RequestBody Consent body){
        auth.requirePlatformAdmin();if(!body.aiConsent())throw ApiException.badRequest("AI_CONSENT_REQUIRED","请同意发送最小功能说明摘要");
        try{var reply=model.interpret(Map.of("message","请解释如何查看网站检测报告。此为运行连通性验证，不包含项目数据。","evidenceIds",List.of()));lastModelCheck=Map.of("status","VERIFIED","checkedAt",OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString(),"message",reply.advice());}
        catch(Exception ex){lastModelCheck=Map.of("status","FAILED","checkedAt",OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString(),"message","真实模型调用未通过，请检查配置或网络；规则模式仍可运行");}return lastModelCheck;
    }
    private Map<String,Object> image(String id,String label,String image){try{commands.run("diagnostics",List.of("docker","image","inspect","--format","{{.Id}}",image),properties.storageRoot().toAbsolutePath().resolve("diagnostics"),Duration.ofSeconds(4),line->{},()->false);return check(id,label,true,"工具镜像已安装");}catch(Exception ex){return check(id,label,false,"请运行启动脚本安装工具镜像");}}
    private Map<String,Object> check(String id,String label,boolean ready,String message){return Map.of("id",id,"label",label,"status",ready?"READY":"UNAVAILABLE","message",message);}
    public record Consent(boolean aiConsent){}
}
