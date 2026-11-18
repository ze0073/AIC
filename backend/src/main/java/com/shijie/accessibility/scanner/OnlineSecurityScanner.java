package com.shijie.accessibility.scanner;

import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.online.TargetScopeService;
import com.shijie.accessibility.online.PinnedHttp;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OnlineSecurityScanner {
    private static final Pattern MIXED = Pattern.compile("(?i)(?:src|href)\\s*=\\s*['\"]http://[^'\"]+");
    private final TargetScopeService scope;
    private final PinnedHttp http;

    public OnlineSecurityScanner(TargetScopeService scope,PinnedHttp http) { this.scope = scope;this.http=http; }

    public Output inspect(OnlineTargetService.TargetView target) {
        List<OnlineFrontendScanner.IssueDraft> issues = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        URI uri = scope.requireAllowed(target, target.baseUrl());
        try {
            PinnedHttp.Result response = http.send(target,uri,"GET",Map.of("User-Agent","Shijie-Security-Checker/0.6.1"),null,12);
            evidence.put("url", uri.toString()); evidence.put("status", response.statusCode());
            evidence.put("headers", sanitizedHeaders(response.headers()));
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                URI upgrade=upgradeDestination(uri,response);
                if(upgrade!=null){
                    evidence.put("httpRedirect",Map.of("status",response.statusCode(),"destination",safeDestination(upgrade),"followed",false));
                    issues.add(new OnlineFrontendScanner.IssueDraft("HTTPS_REDIRECT_UNVERIFIED","TRANSPORT","MEDIUM","INCONCLUSIVE",
                            "已观察到 HTTPS 跳转，目标未验证","HTTP 响应指向 HTTPS；该 HTTPS Origin 未经本次授权，未跟随跳转，也不能据此确认最终证书与页面安全。",
                            Map.of("destination",safeDestination(upgrade),"status",response.statusCode())));
                }else{
                    evidence.put("httpRedirect","NOT_OBSERVED");
                    issues.add(issue("TRANSPORT_HTTP", "HIGH", "站点未使用 HTTPS", "凭据和业务数据可能以明文传输。", Map.of("url", uri.toString())));
                }
            } else {
                inspectCertificate(Optional.ofNullable(response.sslSession()), issues, evidence);
                if (response.firstHeader("strict-transport-security").isEmpty())
                    issues.add(config("HSTS_MISSING", "HTTPS 响应未设置 HSTS", Map.of("url", uri.toString())));
                evidence.put("httpRedirect", "NOT_TESTED_HTTP_ORIGIN_NOT_REGISTERED");
            }
            if (response.firstHeader("content-security-policy").isEmpty())
                issues.add(config("CSP_MISSING", "响应未设置 Content-Security-Policy", Map.of("url", uri.toString())));
            inspectCookies(response.allHeaders("set-cookie"), "https".equalsIgnoreCase(uri.getScheme()), issues);
            Matcher mixed = MIXED.matcher(response.body().substring(0, Math.min(response.body().length(), 500_000)));
            List<String> samples = new ArrayList<>();
            while (mixed.find() && samples.size() < 10) samples.add(mixed.group());
            if ("https".equalsIgnoreCase(uri.getScheme()) && !samples.isEmpty()) issues.add(issue("MIXED_CONTENT", "MEDIUM", "HTTPS 页面引用 HTTP 资源",
                    "页面包含可能被浏览器阻止或被篡改的混合内容。", Map.of("samples", samples)));
        } catch (Exception ex) {
            issues.add(new OnlineFrontendScanner.IssueDraft("TRANSPORT_CHECK_INCONCLUSIVE", "TRANSPORT", "MEDIUM", "INCONCLUSIVE",
                    "传输安全检查无法判断", concise(ex), Map.of("url", uri.toString(), "error", concise(ex))));
            evidence.put("error", concise(ex));
        }
        return new Output(evidence, issues);
    }

    private URI upgradeDestination(URI current,PinnedHttp.Result response){
        if(response.statusCode()<300||response.statusCode()>=400)return null;
        try{
            URI destination=current.resolve(response.firstHeader("location").orElseThrow());
            return "https".equalsIgnoreCase(destination.getScheme())&&destination.getHost()!=null&&destination.getUserInfo()==null?destination:null;
        }catch(Exception ignored){return null;}
    }
    private String safeDestination(URI destination){return destination.getScheme()+"://"+destination.getHost()
            +(destination.getPort()<0?"":":"+destination.getPort())+destination.getPath();}

    private void inspectCertificate(Optional<SSLSession> session, List<OnlineFrontendScanner.IssueDraft> issues, Map<String, Object> evidence) throws Exception {
        if (session.isEmpty() || session.get().getPeerCertificates().length == 0
                || !(session.get().getPeerCertificates()[0] instanceof X509Certificate certificate))
            throw new IllegalStateException("HTTPS 握手缺少可验证的服务端证书");
        long days = ChronoUnit.DAYS.between(Instant.now(), certificate.getNotAfter().toInstant());
        evidence.put("certificateSubject", certificate.getSubjectX500Principal().getName());
        evidence.put("certificateExpiresAt", certificate.getNotAfter().toInstant().toString());
        evidence.put("certificateDaysRemaining", days);
        if (days < 30) issues.add(config("CERTIFICATE_EXPIRING", "TLS 证书即将过期", Map.of("daysRemaining", days)));
    }

    private void inspectCookies(List<String> cookies, boolean https, List<OnlineFrontendScanner.IssueDraft> issues) {
        for (String cookie : cookies) {
            String lower = cookie.toLowerCase(Locale.ROOT);
            String name = cookie.split("=", 2)[0];
            List<String> missing = new ArrayList<>();
            if (https && !lower.contains("; secure")) missing.add("Secure");
            if (!lower.contains("; httponly")) missing.add("HttpOnly");
            if (!lower.contains("; samesite=")) missing.add("SameSite");
            if (!missing.isEmpty()) issues.add(config("COOKIE_FLAGS", "Cookie 安全属性不完整",
                    Map.of("cookieName", name, "missing", missing, "httpsApplicable", https)));
        }
    }

    private OnlineFrontendScanner.IssueDraft issue(String key, String severity, String title, String description, Map<String, Object> evidence) {
        return new OnlineFrontendScanner.IssueDraft(key, "TRANSPORT", severity, "CONFIRMED", title, description, evidence);
    }
    private OnlineFrontendScanner.IssueDraft config(String key, String title, Map<String, Object> evidence) {
        return new OnlineFrontendScanner.IssueDraft(key, "CONFIG", "LOW", "CONFIGURATION", title,
                "该结果是自动配置风险，应结合部署环境和 Cookie 用途理解；可按需审阅。", evidence);
    }
    private Map<String, List<String>> sanitizedHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> result = new TreeMap<>();
        headers.forEach((name, values) -> {
            String lower=name.toLowerCase(Locale.ROOT);
            if("set-cookie".equals(lower))result.put(name,values.stream().map(v->v.split("=",2)[0]+"=<redacted>").toList());
            else if("location".equals(lower))result.put(name,values.stream().map(this::safeLocation).toList());
            else if(lower.matches(".*(authorization|cookie|token|secret|api-key|authenticate).*") )result.put(name,List.of("<redacted>"));
            else result.put(name,values);
        });
        return result;
    }
    private String safeLocation(String value){
        try{
            URI uri=URI.create(value);
            if(uri.isAbsolute())return uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort())+uri.getPath();
            return uri.getPath();
        }catch(Exception ignored){return "<invalid-location>";}
    }
    private static String concise(Exception ex) { String value=ex.getMessage(); return value==null?ex.getClass().getSimpleName():value.lines().findFirst().orElse(value); }
    public record Output(Map<String, Object> evidence, List<OnlineFrontendScanner.IssueDraft> issues) {}
}
