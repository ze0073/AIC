package com.shijie.accessibility.scanner;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.online.TargetScopeService;
import com.shijie.accessibility.online.PinnedHttp;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@Component
public class OnlineFrontendScanner {
    private final TargetScopeService scope;
    private final BrowserScanner browserScanner;
    private final PinnedHttp http;

    public OnlineFrontendScanner(TargetScopeService scope, BrowserScanner browserScanner,PinnedHttp http) { this.scope = scope; this.browserScanner=browserScanner;this.http=http; }

    public Output scan(OnlineTargetService.TargetView target, List<String> requestedPages, BooleanSupplier cancelled) {
        return scan(target,requestedPages,List.of(),cancelled);
    }

    public Output scan(OnlineTargetService.TargetView target, List<String> requestedPages,List<Map<String,Object>> flowSteps, BooleanSupplier cancelled) {
        return scan(target,requestedPages,flowSteps,cancelled,false,true);
    }

    public Output scan(OnlineTargetService.TargetView target, List<String> requestedPages,List<Map<String,Object>> flowSteps,
                       BooleanSupplier cancelled,boolean agent,boolean includeFlow) {
        List<String> pages = requestedPages == null || requestedPages.isEmpty() ? List.of(target.baseUrl())
                : requestedPages.stream().distinct().limit(10).map(value -> resolve(target.baseUrl(), value)).toList();
        List<IssueDraft> issues = new ArrayList<>();
        List<Map<String, Object>> measurements = new ArrayList<>();
        Set<String> links = new LinkedHashSet<>();
        List<String> blocked = new CopyOnWriteArrayList<>();
        List<String> blockedWrites = new CopyOnWriteArrayList<>();
        AtomicBoolean flowWriteWindow=new AtomicBoolean(false);
        Map<String,Object> flowResult=new LinkedHashMap<>();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setArgs(List.of("--host-resolver-rules=MAP "+URI.create(target.baseUrl()).getHost()+" "+scope.pinnedAddress(target),"--no-proxy-server")));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(false)
                     .setServiceWorkers(ServiceWorkerPolicy.BLOCK).setViewportSize(1440, 1000))) {
            context.route("**/*", route -> routePinned(route,target,blocked,blockedWrites,()->!agent||flowWriteWindow.get()));
            context.routeWebSocket("**/*", socket -> {blocked.add(withoutQuery(socket.url()));socket.close();});
            for (String pageUrl : pages) {
                if (cancelled.getAsBoolean()) break;
                scope.requireAllowed(target, pageUrl);
                try (Page page = context.newPage()) {
                    List<String> javascriptErrors = new CopyOnWriteArrayList<>();
                    List<String> requestFailures = new CopyOnWriteArrayList<>();
                    List<Map<String, Object>> responseFailures = new CopyOnWriteArrayList<>();
                    long[] bytes = {0};
                    int[] requests = {0};
                    page.onPageError(error -> javascriptErrors.add(limit(error, 500)));
                    page.onConsoleMessage(message -> {
                        if ("error".equalsIgnoreCase(message.type())) javascriptErrors.add(limit(message.text(), 500));
                    });
                    page.onRequest(request -> requests[0]++);
                    page.onRequestFailed(request -> requestFailures.add(limit(request.url() + " · " + request.failure(), 700)));
                    page.onResponse(response -> {
                        String length = response.headers().get("content-length");
                        if (length != null) try { bytes[0] += Long.parseLong(length); } catch (NumberFormatException ignored) {}
                        if (response.status() >= 400) responseFailures.add(Map.of("url", response.url(), "status", response.status()));
                    });
                    long started = System.nanoTime();
                    page.navigate(pageUrl, new Page.NavigateOptions().setTimeout(15_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    page.waitForTimeout(300);
                    long navigationMs = (System.nanoTime() - started) / 1_000_000;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rules = (Map<String, Object>) page.evaluate(BrowserScanner.RULE_SCRIPT);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> findings = (List<Map<String, Object>>) rules.getOrDefault("findings", List.of());
                    for (Map<String, Object> finding : findings) {
                        issues.add(new IssueDraft(String.valueOf(finding.get("ruleId")), "RULE", String.valueOf(finding.get("severity")), "CONFIRMED",
                                String.valueOf(finding.get("title")), String.valueOf(finding.get("description")), Map.of("pageUrl", pageUrl, "finding", finding)));
                    }
                    Map<String,Object> readingTrace=browserScanner.collectReadingOrder(page);
                    Map<String,Object> focusTrace=browserScanner.collectFocusTrace(page);
                    if ("RISK".equals(readingTrace.get("status"))) issues.add(new IssueDraft("R009","RULE","MEDIUM","MANUAL_REVIEW",
                            "页面阅读顺序自动候选","DOM 顺序与视觉排列存在明显差异；系统不会自动确认辅助技术阅读顺序。",
                            Map.of("pageUrl",pageUrl,"readingTrace",readingTrace)));
                    javascriptErrors.forEach(error -> issues.add(new IssueDraft("JS_EXCEPTION", "BROWSER", "HIGH", "CONFIRMED",
                            "页面发生未捕获 JavaScript 异常", error, Map.of("pageUrl", pageUrl, "message", error))));
                    requestFailures.forEach(error -> issues.add(new IssueDraft("RESOURCE_FAILURE", "BROWSER", "MEDIUM", "CONFIRMED",
                            "页面资源请求失败", error, Map.of("pageUrl", pageUrl, "failure", error))));
                    responseFailures.forEach(failure -> issues.add(new IssueDraft("RESOURCE_HTTP_ERROR", "BROWSER", "MEDIUM", "CONFIRMED",
                            "页面资源返回错误状态", "资源请求返回 HTTP " + failure.get("status"), Map.of("pageUrl", pageUrl, "response", failure))));
                    @SuppressWarnings("unchecked")
                    List<String> discovered = (List<String>) page.evaluate("() => [...document.querySelectorAll('a[href]')].map(a => a.href).filter(Boolean)");
                    discovered.stream().filter(link -> link.startsWith("http://") || link.startsWith("https://")).limit(100).forEach(links::add);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metrics = (Map<String, Object>) page.evaluate("""
                        () => { const n=performance.getEntriesByType('navigation')[0];
                          const p=performance.getEntriesByType('paint').find(x=>x.name==='first-contentful-paint');
                          return {ttfbMs:n?Math.round(n.responseStart):null,domContentLoadedMs:n?Math.round(n.domContentLoadedEventEnd):null,
                            loadMs:n?Math.round(n.loadEventEnd||n.duration):null,fcpMs:p?Math.round(p.startTime):null}; }
                        """);
                    Map<String, Object> measurement = new LinkedHashMap<>(metrics);
                    measurement.put("pageUrl", pageUrl); measurement.put("navigationMs", navigationMs);
                    measurement.put("requestCount", requests[0]); measurement.put("declaredResourceBytes", bytes[0]);
                    measurement.put("readingTrace",readingTrace);measurement.put("focusTrace",focusTrace);
                    List<Map<String,Object>> performanceSamples=measurePagePerformance(browser,target,pageUrl,blocked,blockedWrites,agent);
                    if(!performanceSamples.isEmpty()){
                        Map<String,Object> first=new LinkedHashMap<>(performanceSamples.getFirst());first.put("analysis",measurement);
                        performanceSamples.set(0,first);
                    }
                    measurements.addAll(performanceSamples);
                } catch (Exception ex) {
                    issues.add(new IssueDraft("PAGE_UNAVAILABLE", "BROWSER", "HIGH", "CONFIRMED", "页面无法完成检测",
                            concise(ex), Map.of("pageUrl", pageUrl, "error", concise(ex))));
                }
            }
            if(!cancelled.getAsBoolean()&&flowSteps!=null&&!flowSteps.isEmpty()){
                flowWriteWindow.set(agent&&includeFlow);
                try{runFlow(context,target,flowSteps.stream().limit(20).toList(),issues,flowResult);}
                finally{flowWriteWindow.set(false);}
            }
        } catch (Exception ex) {
            throw new BrowserScanner.ScannerUnavailableException("在线 Chromium 检测失败：" + concise(ex), ex);
        }
        blocked.stream().distinct().limit(20).forEach(url -> issues.add(new IssueDraft("OUT_OF_SCOPE_REQUEST", "SCOPE", "LOW", "CONFIGURATION",
                "页面尝试访问范围外资源", "请求已被在线执行隔离策略阻止。", Map.of("url", url))));
        blockedWrites.stream().distinct().limit(20).forEach(url -> issues.add(new IssueDraft("AGENT_WRITE_BLOCKED","AGENT","LOW","CONFIGURATION",
                "智能体阻止页面写请求","该请求不在本次确认的交互流程执行窗口内。",Map.of("url",url))));
        checkLinks(target, links.stream().limit(100).toList(), issues, cancelled);
        return new Output(measurements, issues, Math.min(100, links.size()), flowResult, cancelled.getAsBoolean());
    }

    private List<Map<String,Object>> measurePagePerformance(Browser browser,OnlineTargetService.TargetView target,String pageUrl,List<String>blocked,List<String>blockedWrites,boolean agent){
        List<Map<String,Object>>samples=new ArrayList<>();
        for(int sample=1;sample<=3;sample++){
            try(BrowserContext cold=browser.newContext(new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK).setViewportSize(1440,1000));Page page=cold.newPage()){
                long[]bytes={0};int[]requests={0};cold.route("**/*",route->routePinned(route,target,blocked,blockedWrites,()->!agent));cold.routeWebSocket("**/*",socket->{blocked.add(withoutQuery(socket.url()));socket.close();});page.onRequest(request->requests[0]++);page.onResponse(response->{String length=response.headers().get("content-length");if(length!=null)try{bytes[0]+=Long.parseLong(length);}catch(Exception ignored){}});page.addInitScript("""
                    (()=>{window.__shijiePerf={lcp:null,cls:0};try{new PerformanceObserver(list=>{const e=list.getEntries();if(e.length)window.__shijiePerf.lcp=Math.round(e[e.length-1].startTime)}).observe({type:'largest-contentful-paint',buffered:true});new PerformanceObserver(list=>{for(const e of list.getEntries())if(!e.hadRecentInput)window.__shijiePerf.cls+=e.value}).observe({type:'layout-shift',buffered:true})}catch(e){}})();
                    """);long started=System.nanoTime();page.navigate(pageUrl,new Page.NavigateOptions().setTimeout(15_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));page.waitForTimeout(500);long wall=(System.nanoTime()-started)/1_000_000;
                @SuppressWarnings("unchecked")Map<String,Object>metrics=(Map<String,Object>)page.evaluate("""
                    () => {const n=performance.getEntriesByType('navigation')[0],m=window.__shijiePerf||{};return{ttfbMs:n?Math.round(n.responseStart):null,navigationMs:n?Math.round(n.duration):null,lcpMs:m.lcp??null,cls:Number.isFinite(m.cls)?Number(m.cls.toFixed(4)):null}}
                    """);Map<String,Object>result=new LinkedHashMap<>(metrics);result.put("pageUrl",pageUrl);result.put("sample",sample);result.put("wallTimeMs",wall);result.put("requestCount",requests[0]);result.put("declaredResourceBytes",bytes[0]);samples.add(result);
            }catch(Exception ex){samples.add(Map.of("pageUrl",pageUrl,"sample",sample,"status","UNAVAILABLE","error",concise(ex)));}
        }return samples;
    }

    private void routePinned(Route route,OnlineTargetService.TargetView target,List<String>blocked,List<String>blockedWrites,BooleanSupplier writeAllowed){
        try{
            URI current=scope.requireAllowed(target,route.request().url());
            String method=route.request().method();
            if(!agentMethodAllowed(method,writeAllowed.getAsBoolean())){
                blockedWrites.add(withoutQuery(route.request().url()));
                route.abort("blockedbyclient");
                return;
            }
            byte[]body=route.request().postDataBuffer();
            Map<String,String>headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(route.request().headers());
            for(String name:List.of("Host","Connection","Content-Length","Transfer-Encoding","Accept-Encoding","Proxy-Connection","Upgrade"))headers.remove(name);
            headers.put("Accept-Encoding","identity");
            for(int redirects=0;redirects<=5;redirects++){
                PinnedHttp.Result response=http.send(target,current,method,headers,body,15);
                if(response.statusCode()>=300&&response.statusCode()<400&&response.firstHeader("location").isPresent()){
                    URI candidate=current.resolve(response.firstHeader("location").orElseThrow());
                    try{current=scope.requireAllowed(target,candidate.toString());}
                    catch(com.shijie.accessibility.common.ApiException outside){
                        blocked.add(withoutQuery(candidate.toString()));
                        route.abort("blockedbyclient");
                        return;
                    }
                    if(response.statusCode()==303||((response.statusCode()==301||response.statusCode()==302)&&!"GET".equals(method)&&!"HEAD".equals(method))){
                        method="GET";body=null;headers.remove("Content-Type");
                    }
                    continue;
                }
                Map<String,String>responseHeaders=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                response.headers().forEach((name,values)->{
                    if(!List.of("content-length","content-encoding","transfer-encoding","connection","location").contains(name.toLowerCase(Locale.ROOT))&&!values.isEmpty())
                        responseHeaders.put(name,values.getFirst());
                });
                responseHeaders.put("Content-Length",String.valueOf(response.bodyBytes().length));
                route.fulfill(new Route.FulfillOptions().setStatus(response.statusCode()).setHeaders(responseHeaders).setBodyBytes(response.bodyBytes()));
                return;
            }
            route.abort("failed");
        }catch(com.shijie.accessibility.common.ApiException outside){
            blocked.add(withoutQuery(route.request().url()));
            route.abort("blockedbyclient");
        }catch(Exception ex){route.abort("failed");}
    }

    static boolean agentMethodAllowed(String method,boolean writeAllowed){
        return writeAllowed||Set.of("GET","HEAD","OPTIONS").contains(method.toUpperCase(Locale.ROOT));
    }

    private static String withoutQuery(String url){
        int query=url.indexOf('?');
        int fragment=url.indexOf('#');
        int end=query<0?fragment:fragment<0?query:Math.min(query,fragment);
        return end<0?url:url.substring(0,end);
    }

    private void runFlow(BrowserContext context,OnlineTargetService.TargetView target,List<Map<String,Object>>steps,List<IssueDraft>issues,Map<String,Object>result){
        List<Map<String,Object>>completed=new ArrayList<>();result.put("totalSteps",steps.size());
        try(Page page=context.newPage()){
            page.setDefaultTimeout(10_000);
            for(int index=0;index<steps.size();index++){
                Map<String,Object>step=steps.get(index);String action=String.valueOf(step.getOrDefault("action","ASSERT_VISIBLE")).toUpperCase();String selector=String.valueOf(step.getOrDefault("selector",""));long started=System.nanoTime();
                try{switch(action){case"NAVIGATE"->{String url=resolve(target.baseUrl(),String.valueOf(step.getOrDefault("url",target.baseUrl())));scope.requireAllowed(target,url);page.navigate(url,new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));}case"CLICK"->page.locator(selector).click();case"FILL"->page.locator(selector).fill(String.valueOf(step.getOrDefault("value","")));case"ASSERT_VISIBLE"->{if(!page.locator(selector).isVisible())throw new AssertionError("元素不可见");}case"ASSERT_TEXT"->{String actual=page.locator(selector).innerText();String expected=String.valueOf(step.getOrDefault("expected",""));if(!actual.contains(expected))throw new AssertionError("文本断言失败");}default->throw new IllegalArgumentException("不支持的流程动作："+action);}completed.add(Map.of("index",index+1,"action",action,"selector",selector,"durationMs",(System.nanoTime()-started)/1_000_000));}
                catch(Throwable failure){byte[]shot=page.screenshot(new Page.ScreenshotOptions().setFullPage(true));Map<String,Object>evidence=new LinkedHashMap<>();evidence.put("step",index+1);evidence.put("action",action);evidence.put("selector",selector);evidence.put("error",failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());evidence.put("completedSteps",completed);evidence.put("screenshotBase64",Base64.getEncoder().encodeToString(shot));issues.add(new IssueDraft("FLOW_STEP_FAILED","FLOW","HIGH","REPRODUCIBLE_ASSERTION","交互流程在第 "+(index+1)+" 步失败",String.valueOf(evidence.get("error")),evidence));result.put("status","FAILED");result.put("failedStep",index+1);result.put("completedSteps",completed);return;}
            }result.put("status","COMPLETED");result.put("completedSteps",completed);
        }
    }

    private void checkLinks(OnlineTargetService.TargetView target, List<String> links, List<IssueDraft> issues, BooleanSupplier cancelled) {
        for (String link : links) {
            if (cancelled.getAsBoolean()) return;
            try {
                URI current = scope.requireAllowed(target, link);
                int redirects = 0;
                while (true) {
                    PinnedHttp.Result response = http.send(target,current,"GET",Map.of("User-Agent","Shijie-Quality-Checker/0.6.1"),null,8);
                    if (response.statusCode() >= 300 && response.statusCode() < 400 && response.firstHeader("location").isPresent()) {
                        if (++redirects > 5) throw new IllegalStateException("重定向次数超过 5 次");
                        current = scope.requireAllowed(target, current.resolve(response.firstHeader("location").orElseThrow()).toString());
                        continue;
                    }
                    if (response.statusCode() >= 400) issues.add(new IssueDraft("BROKEN_LINK", "HTTP", "MEDIUM", "CONFIRMED",
                            "同站链接不可用", "链接返回 HTTP " + response.statusCode(), Map.of("url", link, "status", response.statusCode())));
                    break;
                }
            } catch (com.shijie.accessibility.common.ApiException ignored) {
                // Only same-origin, registered-path links are checked.
            } catch (Exception ex) {
                issues.add(new IssueDraft("BROKEN_LINK", "HTTP", "MEDIUM", "CONFIRMED", "同站链接检查失败",
                        concise(ex), Map.of("url", link, "error", concise(ex))));
            }
        }
    }

    private static String resolve(String base, String value) { return URI.create(base).resolve(value).toString(); }
    private static String limit(String value, int max) { if (value == null) return "未知错误"; return value.length() <= max ? value : value.substring(0, max); }
    private static String concise(Exception ex) { String value = ex.getMessage(); return limit(value == null ? ex.getClass().getSimpleName() : value.lines().findFirst().orElse(value), 700); }

    public record IssueDraft(String key, String source, String severity, String confidence, String title,
                             String description, Map<String, Object> evidence) {}
    public record Output(List<Map<String, Object>> measurements, List<IssueDraft> issues, int checkedLinks, Map<String,Object> flow, boolean cancelled) {}
}
