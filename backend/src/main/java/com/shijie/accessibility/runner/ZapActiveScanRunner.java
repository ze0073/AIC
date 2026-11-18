package com.shijie.accessibility.runner;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.online.TargetScopeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ZapActiveScanRunner {
    private final ToolProcessRunner processes;
    private final AppProperties properties;
    private final TargetScopeService scope;
    private final ObjectMapper json;
    private final String image;

    public ZapActiveScanRunner(ToolProcessRunner processes, AppProperties properties,TargetScopeService scope,ObjectMapper json,
                               @Value("${app.tools.zap-image:zaproxy/zap-stable:2.16.1}") String image) {
        this.processes = processes; this.properties = properties; this.scope=scope; this.json=json; this.image = image;
    }

    public Output run(String runId, OnlineTargetService.TargetView target, List<Map<String, Object>> apiCases) {
        if (!target.allowActiveScan()) return new Output("SKIPPED", image, -1, "", "目标未授权主动扫描", null);
        List<Map<String,Object>> scanCases = apiCases == null ? List.of() : apiCases.stream().filter(this::eligible).limit(10).toList();
        if (scanCases.isEmpty()) return new Output("SKIPPED", image, -1, "", "套件未登记含明确参数的 GET 主动扫描接口", null);
        Path work = properties.storageRoot().toAbsolutePath().normalize().resolve("test-runs").resolve(runId).resolve("zap");
        try {
            for(Map<String,Object> item:scanCases)scope.requireAllowed(target,URI.create(target.baseUrl()).resolve(String.valueOf(item.get("path"))).toString());
            Files.createDirectories(work);
            Files.writeString(work.resolve("scope-guard.js"),scopeGuard(target));
            Files.writeString(work.resolve("automation.yaml"), plan(target, scanCases));
            String mount = work.toString().replace('\\', '/');
            List<String> command = List.of("docker", "run", "--rm", "--pull=never", "--add-host",URI.create(target.baseUrl()).getHost()+":"+scope.toolAddress(target), "--name", "shijie-zap-" + runId.substring(0, 8).toLowerCase(),
                    "--memory", "1g", "--cpus", "1", "-v", mount + ":/zap/wrk:rw", image,
                    "zap.sh", "-cmd", "-autorun", "/zap/wrk/automation.yaml");
            ToolProcessRunner.Result result = processes.run(runId, command, work, Duration.ofMinutes(10));
            String report = Files.exists(work.resolve("zap-report.json")) ? Files.readString(work.resolve("zap-report.json")) : null;
            if ("COMPLETED".equals(result.status()) && !validReport(report))
                return new Output("FAILED", image, result.exitCode(), result.output(), "ZAP 未生成有效扫描报告", report);
            return new Output(result.status(), image, result.exitCode(), result.output(), result.error(), report);
        } catch (Exception ex) {
            return new Output("FAILED", image, -1, "", ex.getMessage(), null);
        }
    }

    private boolean validReport(String report) {
        if (report == null || report.isBlank()) return false;
        try {
            Map<?,?> root=json.readValue(report,Map.class);
            return root.get("site") instanceof List<?> sites && !sites.isEmpty();
        } catch (Exception ex) { return false; }
    }

    public String image() { return image; }
    public String digest() { return processes.imageDigest(image); }
    public void cancel(String runId) { processes.cancel(runId); }

    public boolean eligible(Map<String,Object> item) {
        if (!Boolean.TRUE.equals(item.get("activeScan")) || !"GET".equalsIgnoreCase(String.valueOf(item.getOrDefault("method","GET")))) return false;
        if (!(item.get("activeParameters") instanceof List<?> parameters) || parameters.isEmpty()) return false;
        URI uri;
        try { uri = URI.create(String.valueOf(item.getOrDefault("path","/"))); } catch(Exception ex) { return false; }
        if (uri.getRawQuery() == null) return false;
        Set<String> declared = new LinkedHashSet<>();
        for (Object parameter : parameters) declared.add(String.valueOf(parameter));
        Set<String> present = new LinkedHashSet<>();
        for (String pair : uri.getRawQuery().split("&")) present.add(URLDecoder.decode(pair.split("=",2)[0], StandardCharsets.UTF_8));
        return present.equals(declared);
    }

    String plan(OnlineTargetService.TargetView target, List<Map<String, Object>> apiCases) {
        List<String> urls = apiCases.stream().map(item -> URI.create(target.baseUrl())
                .resolve(String.valueOf(item.get("path"))).toString()).distinct().toList();
        String includes = urls.stream().map(value -> {
            URI uri = URI.create(value);
            return yaml("^" + Pattern.quote(target.allowedOrigin() + uri.getRawPath()) + "(?:\\?.*)?$");
        }).reduce((a,b) -> a + ", " + b).orElseThrow();
        StringBuilder requests = new StringBuilder();
        for (String value : urls) requests.append("      - url: ").append(yaml(value)).append("\n        method: GET\n");
        return """
            env:
              contexts:
                - name: shijie-target
                  urls: [%s]
                  includePaths: [%s]
              parameters:
                failOnError: true
                failOnWarning: false
                progressToStdout: true
            jobs:
              - type: script
                parameters:
                  action: add
                  type: httpsender
                  engine: 'ECMAScript : Graal.js'
                  name: shijie-scope-guard
                  source: /zap/wrk/scope-guard.js
              - type: requestor
                requests:
            %s
              - type: activeScan-policy
                parameters:
                  name: shijie-sqli-xss-only
                policyDefinition:
                  defaultStrength: Medium
                  defaultThreshold: 'OFF'
                  rules:
                    - id: 40012
                      threshold: Medium
                      strength: Medium
                    - id: 40018
                      threshold: Medium
                      strength: Medium
              - type: activeScan
                parameters:
                  context: shijie-target
                  policy: shijie-sqli-xss-only
                  threadPerHost: 1
                  delayInMs: 500
                  addQueryParam: false
                  scanHeadersAllRequests: false
                  maxScanDurationInMins: 9
                  maxRuleDurationInMins: 4
              - type: report
                parameters:
                  template: traditional-json
                  reportDir: /zap/wrk
                  reportFile: zap-report.json
            """.formatted(yaml(target.baseUrl()), includes, requests.toString().stripTrailing());
    }
    String scopeGuard(OnlineTargetService.TargetView target){
        URI base=URI.create(target.baseUrl());
        String paths=target.pathPrefixes().stream().map(this::jsLiteral).reduce((a,b)->a+","+b).orElse("'/'");
        return """
            var URI = Java.type('java.net.URI');
            var IOException = Java.type('java.io.IOException');
            var expectedScheme = __SCHEME__;
            var expectedHost = __HOST__;
            var expectedPort = __PORT__;
            var allowedPrefixes = [__PATHS__];
            function sendingRequest(msg, initiator, helper) {
              var uri;
              try { uri = new URI(msg.getRequestHeader().getURI().toString()); }
              catch (e) { throw new IOException('Blocked malformed request URI'); }
              var scheme = String(uri.getScheme()).toLowerCase();
              var host = String(uri.getHost()).toLowerCase();
              var port = uri.getPort();
              if (port < 0) port = scheme === 'https' ? 443 : 80;
              var path = String(uri.getRawPath() || '/');
              var allowed = allowedPrefixes.some(function(prefix) { return prefix === '/' || (path.endsWith('/') ? path : path + '/').startsWith(prefix); });
              if (scheme !== expectedScheme || host !== expectedHost || port !== expectedPort || !allowed || path.indexOf('%%') >= 0 || path.indexOf('\\\\') >= 0 || path.indexOf(';') >= 0) {
                throw new IOException('Blocked out-of-scope request before network send');
              }
            }
            function responseReceived(msg, initiator, helper) {}
            """.replace("__SCHEME__",jsLiteral(base.getScheme().toLowerCase())).replace("__HOST__",jsLiteral(base.getHost().toLowerCase()))
                .replace("__PORT__",String.valueOf(base.getPort()<0?("https".equalsIgnoreCase(base.getScheme())?443:80):base.getPort()))
                .replace("__PATHS__",paths).replace("%%","%").replace("var uri;","var access = "+jsLiteral(scope.requestHeaders(target).getOrDefault("X-Shijie-Access",""))+"; if (access) msg.getRequestHeader().setHeader('X-Shijie-Access',access); var uri;");
    }
    private String jsLiteral(String value){return "'"+value.replace("\\","\\\\").replace("'","\\'")+"'";}
    private String yaml(String value) { return "'" + value.replace("'", "''") + "'"; }
    public record Output(String status, String image, int exitCode, String logTail, String error, String reportJson) {}
}
