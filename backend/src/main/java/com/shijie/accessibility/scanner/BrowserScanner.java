package com.shijie.accessibility.scanner;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.model.ProjectVersionEntity;
import com.shijie.accessibility.service.StorageService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

@Component
public class BrowserScanner {
    private static final int MAX_FOCUS_STEPS = 30;
    private static final int MAX_ORDER_ELEMENTS = 80;
    private final StorageService storage;
    private final AppProperties properties;

    public BrowserScanner(StorageService storage, AppProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    public ScanOutput scan(ProjectVersionEntity version, List<String> requestedPages, String scanId,
                           BooleanSupplier cancelled) {
        return scan(version, requestedPages, scanId, true, true, cancelled);
    }

    public ScanOutput scan(ProjectVersionEntity version, List<String> requestedPages, String scanId,
                           boolean collectKeyboard, boolean collectReadingOrder, BooleanSupplier cancelled) {
        List<PageOutput> pages = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> selected = requestedPages == null || requestedPages.isEmpty()
                ? List.of(version.entryPage) : requestedPages.stream().distinct()
                .limit(properties.scanner().maxPages()).toList();
        long deadline = System.currentTimeMillis() + properties.scanner().totalTimeoutMs();

        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                     .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setAcceptDownloads(false).setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                     .setViewportSize(1440, 1000))) {
            context.route("**/*", route -> fulfillProjectRequest(route, version));
            for (String pagePath : selected) {
                if (cancelled.getAsBoolean()) break;
                if (System.currentTimeMillis() > deadline) {
                    errors.add("扫描总时限已到，未继续处理页面：" + pagePath);
                    break;
                }
                try (Page page = context.newPage()) {
                    page.setDefaultTimeout(properties.scanner().pageTimeoutMs());
                    page.navigate("http://project.local/" + encodePath(pagePath),
                            new Page.NavigateOptions().setTimeout(properties.scanner().pageTimeoutMs())
                                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
                    page.waitForTimeout(150);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ruleOutput = (Map<String, Object>) page.evaluate(RULE_SCRIPT);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> raw = (List<Map<String, Object>>) ruleOutput.getOrDefault("findings", List.of());
                    @SuppressWarnings("unchecked")
                    List<String> ruleErrors = (List<String>) ruleOutput.getOrDefault("ruleErrors", List.of());
                    ruleErrors.forEach(error -> errors.add(pagePath + "：" + error));
                    Map<String, Object> readingTrace = collectReadingOrder
                            ? collectReadingOrder(page) : Map.of("status", "DISABLED");
                    Map<String, Object> focusTrace = collectKeyboard
                            ? collectFocusTrace(page) : Map.of("status", "DISABLED");
                    Path screenshots = storage.scanDirectory(version.projectId, scanId).resolve("screenshots");
                    Files.createDirectories(screenshots);
                    Path screenshot = screenshots.resolve(safeScreenshotName(pagePath));
                    page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setFullPage(true));
                    List<CollectedFinding> findings = new ArrayList<>(raw.stream().map(this::mapFinding).toList());
                    if ("RISK".equals(String.valueOf(readingTrace.get("status")))) {
                        Map<String, Object> evidence = new LinkedHashMap<>(readingTrace);
                        evidence.put("traceType", "READING_ORDER");
                        findings.add(new CollectedFinding("R009", "MEDIUM", "页面阅读顺序自动候选",
                                "DOM 顺序与视觉排列存在明显差异；系统不会自动确认辅助技术阅读顺序是否合理。",
                                "html", "NONE", evidence));
                    }
                    pages.add(new PageOutput(pagePath, screenshot, findings, focusTrace, readingTrace));
                } catch (Exception ex) {
                    errors.add(pagePath + "：" + concise(ex));
                }
            }
        } catch (Exception ex) {
            throw new ScannerUnavailableException("Chromium 启动或扫描失败：" + concise(ex), ex);
        }
        return new ScanOutput(pages, errors, cancelled.getAsBoolean());
    }

    private void fulfillProjectRequest(Route route, ProjectVersionEntity version) {
        try {
            URI uri = URI.create(route.request().url());
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !"project.local".equalsIgnoreCase(uri.getHost())) {
                route.abort("blockedbyclient");
                return;
            }
            String relative = URLDecoder.decode(uri.getPath(), StandardCharsets.UTF_8);
            relative = relative.startsWith("/") ? relative.substring(1) : relative;
            if (relative.isBlank()) relative = version.entryPage;
            Path file = storage.resolveVersionFile(version.projectId, version.id, relative);
            String mime = Optional.ofNullable(Files.probeContentType(file)).orElse("application/octet-stream");
            route.fulfill(new Route.FulfillOptions().setPath(file).setContentType(mime)
                    .setHeaders(Map.of("Content-Security-Policy",
                            "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'none'; object-src 'none'; base-uri 'none'")));
        } catch (Exception ex) {
            route.abort("blockedbyclient");
        }
    }

    Map<String, Object> collectFocusTrace(Page page) {
        Map<String, Object> result = new LinkedHashMap<>();
        Traversal forward = traverse(page, "Tab");
        Traversal reverse = traverse(page, "Shift+Tab");
        result.put("status", forward.steps().isEmpty() && reverse.steps().isEmpty() ? "EMPTY"
                : forward.truncated() || reverse.truncated() ? "PARTIAL" : "COMPLETE");
        result.put("forwardSteps", forward.steps());
        result.put("reverseSteps", reverse.steps());
        result.put("maxSteps", MAX_FOCUS_STEPS);
        result.put("cycleDetected", forward.cycleDetected() || reverse.cycleDetected());
        result.put("truncated", forward.truncated() || reverse.truncated());
        result.put("noFocusTarget", forward.steps().isEmpty() && reverse.steps().isEmpty());
        return result;
    }

    private Traversal traverse(Page page, String key) {
        List<Map<String, Object>> steps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        page.evaluate("""
                () => {
                  const body = document.body;
                  if (!body) return;
                  const previous = body.getAttribute('tabindex');
                  body.setAttribute('tabindex','-1'); body.focus({preventScroll:true});
                  if (previous === null) body.removeAttribute('tabindex'); else body.setAttribute('tabindex', previous);
                }
                """);
        boolean cycle = false;
        for (int index = 1; index <= MAX_FOCUS_STEPS; index++) {
            page.keyboard().press(key);
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) page.evaluate(FOCUS_STEP_SCRIPT);
            String selector = String.valueOf(item.getOrDefault("selector", "body"));
            if ("body".equals(selector) || "none".equals(selector)) continue;
            if (!seen.add(selector)) { cycle = true; break; }
            item = new LinkedHashMap<>(item);
            item.put("index", index);
            steps.add(item);
        }
        return new Traversal(steps, cycle, steps.size() >= MAX_FOCUS_STEPS && !cycle);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> collectReadingOrder(Page page) {
        Map<String, Object> result = (Map<String, Object>) page.evaluate(READING_ORDER_SCRIPT,
                Map.of("maxElements", MAX_ORDER_ELEMENTS));
        return result == null ? Map.of("status", "UNSUPPORTED", "reason", "无法采集阅读顺序") : result;
    }

    private CollectedFinding mapFinding(Map<String, Object> item) {
        String ruleId = String.valueOf(item.get("ruleId"));
        Map<String, Object> evidence = new LinkedHashMap<>(item);
        if (ruleId.equals("R007")) evidence.put("traceType", "KEYBOARD_FOCUS");
        return new CollectedFinding(ruleId, string(item, "severity"), string(item, "title"),
                string(item, "description"), string(item, "selector"), string(item, "fixMode"), evidence);
    }

    private String string(Map<String, Object> map, String key) { return String.valueOf(map.getOrDefault(key, "")); }
    private String encodePath(String value) { return value.replace(" ", "%20"); }
    private String safeScreenshotName(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_") + ".png"; }
    private String concise(Exception ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message.lines().findFirst().orElse(message);
    }

    public record CollectedFinding(String ruleId, String severity, String title, String description,
                                   String selector, String fixMode, Map<String, Object> evidence) {}
    public record PageOutput(String pagePath, Path screenshot, List<CollectedFinding> findings,
                             Map<String, Object> focusTrace, Map<String, Object> readingTrace) {}
    public record ScanOutput(List<PageOutput> pages, List<String> pageErrors, boolean cancelled) {}
    private record Traversal(List<Map<String, Object>> steps, boolean cycleDetected, boolean truncated) {}

    public static class ScannerUnavailableException extends RuntimeException {
        public ScannerUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    static final String FOCUS_STEP_SCRIPT = """
        () => {
          const e = document.activeElement;
          if (!e || e === document.body || e === document.documentElement) return {selector:'body',visible:false};
          const cssPath = element => {
            if (element.id) return '#' + CSS.escape(element.id);
            const parts=[]; let node=element;
            while(node && node.nodeType===1 && node!==document.documentElement){
              let part=node.tagName.toLowerCase();
              const siblings=node.parentElement ? [...node.parentElement.children].filter(x=>x.tagName===node.tagName) : [];
              if(siblings.length>1) part += `:nth-of-type(${siblings.indexOf(node)+1})`;
              parts.unshift(part); node=node.parentElement;
            }
            return parts.join(' > ');
          };
          const name = element => {
            const aria=(element.getAttribute('aria-label')||'').trim(); if(aria)return aria.slice(0,160);
            const ids=(element.getAttribute('aria-labelledby')||'').split(/\\s+/).filter(Boolean);
            const labelled=ids.map(id=>document.getElementById(id)?.textContent||'').join(' ').trim(); if(labelled)return labelled.slice(0,160);
            if(element.labels?.length){const value=[...element.labels].map(x=>x.textContent||'').join(' ').trim();if(value)return value.slice(0,160)}
            const text=(element.innerText||element.getAttribute('alt')||element.getAttribute('title')||element.getAttribute('placeholder')||'').trim();
            return text.replace(/\\s+/g,' ').slice(0,160);
          };
          const s=getComputedStyle(e), r=e.getBoundingClientRect();
          const visible=!!(r.width&&r.height)&&s.visibility!=='hidden'&&s.display!=='none';
          const outlineVisible=s.outlineStyle!=='none'&&parseFloat(s.outlineWidth||'0')>0;
          const shadowVisible=s.boxShadow!=='none'&&s.boxShadow!=='';
          return {selector:cssPath(e),tag:e.tagName.toLowerCase(),role:e.getAttribute('role')||'',
            accessibleName:name(e),tabindex:e.getAttribute('tabindex')??'DEFAULT',visible,
            boundingBox:{x:Math.round((r.x+scrollX)/4)*4,y:Math.round((r.y+scrollY)/4)*4,
              width:Math.round(r.width/4)*4,height:Math.round(r.height/4)*4},
            outline:`${s.outlineStyle} ${s.outlineWidth} ${s.outlineColor}`,boxShadow:s.boxShadow,
            focusIndicator:outlineVisible||shadowVisible?'VISIBLE':'POSSIBLY_HIDDEN',focusVisible:e.matches(':focus-visible')};
        }
        """;

    static final String READING_ORDER_SCRIPT = """
        ({maxElements}) => {
          const rootStyle=getComputedStyle(document.documentElement);
          const writingMode=rootStyle.writingMode||'horizontal-tb';
          const direction=rootStyle.direction||document.dir||'ltr';
          if(writingMode!=='horizontal-tb') return {status:'UNSUPPORTED',reason:`暂不支持 ${writingMode} 书写模式`,writingMode,direction};
          const cssPath = element => {
            if(element.id) return '#' + CSS.escape(element.id);
            const parts=[]; let node=element;
            while(node && node.nodeType===1 && node!==document.documentElement){
              let part=node.tagName.toLowerCase();
              const siblings=node.parentElement ? [...node.parentElement.children].filter(x=>x.tagName===node.tagName) : [];
              if(siblings.length>1) part += `:nth-of-type(${siblings.indexOf(node)+1})`;
              parts.unshift(part); node=node.parentElement;
            }
            return parts.join(' > ');
          };
          const label = element => (element.getAttribute('aria-label')||element.innerText||element.getAttribute('alt')||
            element.getAttribute('placeholder')||element.getAttribute('title')||'').replace(/\\s+/g,' ').trim().slice(0,160);
          const selector='h1,h2,h3,h4,h5,h6,p,li,label,input:not([type=hidden]),select,textarea,button,a[href],td,th,[role=button],[role=link],[role=heading]';
          const candidates=[];
          for(const e of document.querySelectorAll(selector)){
            if(candidates.length>=maxElements)break;
            const s=getComputedStyle(e),r=e.getBoundingClientRect();
            if(e.closest('[aria-hidden=true]')||s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity||'1')===0||r.width<2||r.height<2)continue;
            const text=label(e); if(!text&&!["INPUT","SELECT","TEXTAREA"].includes(e.tagName))continue;
            candidates.push({selector:cssPath(e),tag:e.tagName.toLowerCase(),role:e.getAttribute('role')||'',name:text,
              outerHtml:e.outerHTML.slice(0,500),domIndex:candidates.length,order:Number.parseInt(s.order||'0',10)||0,
              styleSignature:{display:s.display,position:s.position,order:s.order,flexDirection:s.flexDirection,
                gridArea:s.gridArea,transform:s.transform,writingMode:s.writingMode,direction:s.direction},
              boundingBox:{x:Math.round((r.x+scrollX)/4)*4,y:Math.round((r.y+scrollY)/4)*4,
                width:Math.max(4,Math.round(r.width/4)*4),height:Math.max(4,Math.round(r.height/4)*4)}});
          }
          const sameRow=(a,b)=>{const ar=a.boundingBox,br=b.boundingBox;const overlap=Math.min(ar.y+ar.height,br.y+br.height)-Math.max(ar.y,br.y);return overlap>Math.min(ar.height,br.height)*.5||Math.abs(ar.y-br.y)<16};
          const visual=[...candidates].sort((a,b)=>{
            if(sameRow(a,b))return direction==='rtl'?b.boundingBox.x-a.boundingBox.x:a.boundingBox.x-b.boundingBox.x;
            return a.boundingBox.y-b.boundingBox.y;
          }).map((item,index)=>({...item,visualIndex:index}));
          const bySelector=new Map(visual.map(x=>[x.selector,x]));
          const dom=candidates.map(x=>({...x,visualIndex:bySelector.get(x.selector)?.visualIndex??x.domIndex}));
          let comparable=0, inversionCount=0; const inversions=[];
          for(let i=0;i<dom.length;i++)for(let j=i+1;j<dom.length;j++){
            const a=dom[i],b=dom[j],ar=a.boundingBox,br=b.boundingBox,row=sameRow(a,b);
            const vertical=br.y<=ar.y-16;
            const horizontal=row&&(direction==='rtl'?br.x>=ar.x+16:br.x<=ar.x-16);
            const forwardVertical=ar.y<=br.y-16;
            const forwardHorizontal=row&&(direction==='rtl'?ar.x>=br.x+16:ar.x<=br.x-16);
            if(vertical||horizontal||forwardVertical||forwardHorizontal)comparable++;
            if((vertical||horizontal)&&a.visualIndex>b.visualIndex){
              inversionCount++;
              if(inversions.length<20) inversions.push({domBefore:a.selector,visuallyBefore:b.selector,domIndexA:a.domIndex,domIndexB:b.domIndex,
                visualIndexA:a.visualIndex,visualIndexB:b.visualIndex});
            }
          }
          const ratio=comparable?inversionCount/comparable:0;
          const explicitOrder=dom.filter(x=>x.order!==0);
          const sequenceChanged=dom.some(x=>x.domIndex!==x.visualIndex);
          const risk=dom.length>=4&&((inversionCount>=2&&ratio>=.15)||(explicitOrder.length>0&&sequenceChanged));
          return {status:risk?'RISK':'OK',writingMode,direction,candidateCount:dom.length,maxElements,
            truncated:dom.length>=maxElements,comparablePairs:comparable,inversionCount,
            inversionRatio:Number(ratio.toFixed(3)),domSequence:dom,visualSequence:visual,
            inversions,explicitOrder:explicitOrder.map(x=>({selector:x.selector,order:x.order}))};
        }
        """;

    static final String RULE_SCRIPT = """
        () => {
          const out = [], ruleErrors = [];
          const run = (ruleId, check) => { try { check(); } catch (error) { ruleErrors.push(ruleId + '：' + String(error?.message||error)); } };
          const visible = e => { const s=getComputedStyle(e), r=e.getBoundingClientRect(); return s.display!=='none' && s.visibility!=='hidden' && r.width>0 && r.height>0; };
          const selector = e => {
            if (e.id) return '#' + CSS.escape(e.id);
            const parts=[]; let n=e;
            while(n && n.nodeType===1 && parts.length<5){ let p=n.tagName.toLowerCase();
              if(n.parentElement){const same=[...n.parentElement.children].filter(x=>x.tagName===n.tagName); if(same.length>1)p+=`:nth-of-type(${same.indexOf(n)+1})`;}
              parts.unshift(p); n=n.parentElement;
            } return parts.join(' > ');
          };
          const box = e => { const r=e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height}; };
          const add = (ruleId,severity,title,description,e,fixMode,details={}) => out.push({ruleId,severity,title,description,
            selector:e?selector(e):'html',fixMode,outerHtml:e?e.outerHTML.slice(0,2000):document.documentElement.outerHTML.slice(0,500),
            boundingBox:e?box(e):box(document.documentElement),details});
          const name = (e,includeContents=true) => (e.getAttribute('aria-label')||'').trim() ||
            (e.getAttribute('aria-labelledby')||'').split(/\\s+/).map(id=>document.getElementById(id)?.textContent||'').join(' ').trim() ||
            (e.id ? (document.querySelector(`label[for="${CSS.escape(e.id)}"]`)?.textContent||'').trim() : '') ||
            (e.closest('label')?.textContent||'').trim() || (e.getAttribute('alt')||'').trim() ||
            [...e.querySelectorAll('img[alt]')].map(img=>img.getAttribute('alt')||'').join(' ').trim() ||
            (includeContents ? (e.textContent||'').trim() : '') || (e.getAttribute('title')||'').trim();
          const hasNearbyUnboundLabel = e => e.id && e.previousElementSibling?.matches('label:not([for])') &&
            (e.previousElementSibling.textContent||'').trim() !== '';

          run('R001',()=>document.querySelectorAll('img').forEach(e => { if(visible(e) && !e.hasAttribute('alt')) add('R001','HIGH','图片缺少替代文本','屏幕阅读器无法获知该图片表达的内容。',e,'SUGGESTION'); }));
          run('R002',()=>document.querySelectorAll('input:not([type=hidden]),select,textarea').forEach(e => { if(visible(e) && !name(e,false)) add('R002','HIGH','表单控件缺少名称','控件没有关联标签或可访问名称。',e,hasNearbyUnboundLabel(e)?'TEMPLATE_PATCH':'SUGGESTION'); }));
          run('R003',()=>document.querySelectorAll('button,a[href],[role=button],[role=link]').forEach(e => { if(visible(e) && !name(e)) add('R003','HIGH','按钮或链接缺少名称','辅助技术无法描述该控件的用途。',e,'SUGGESTION'); }));

          const html=document.documentElement;
          run('R004',()=>{ const lang=(html.getAttribute('lang')||'').trim();
            if(!/^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$/.test(lang)) add('R004','MEDIUM','页面语言缺失','页面根元素需要合法的 lang 属性。',html,'TEMPLATE_PATCH',{lang}); });

          run('R005',()=>{ const hs=[...document.querySelectorAll('h1,h2,h3,h4,h5,h6')].filter(visible);
            if(!hs.some(h=>h.tagName==='H1')) add('R005','LOW','页面缺少主标题','可见标题序列中没有 H1。',html,'NONE',{headings:hs.map(h=>h.tagName+': '+h.textContent.trim())});
            for(let i=1;i<hs.length;i++){ const a=+hs[i-1].tagName[1], b=+hs[i].tagName[1]; if(b>a+1) add('R005','LOW','标题层级跳跃',`标题从 H${a} 跳到 H${b}。`,hs[i],'NONE',{previous:selector(hs[i-1])}); } });

          const color = value => { const n=(value.match(/[\\d.]+/g)||[]).map(Number); return n.length>=3?[n[0],n[1],n[2],n.length>3?n[3]:1]:null; };
          const lum = c => { const v=c.slice(0,3).map(x=>{x/=255;return x<=.04045?x/12.92:Math.pow((x+.055)/1.055,2.4)}); return .2126*v[0]+.7152*v[1]+.0722*v[2]; };
          const bg = e => { let n=e; while(n){const c=color(getComputedStyle(n).backgroundColor);if(c&&c[3]>=.99)return c;n=n.parentElement;}return [255,255,255,1]; };
          run('R006',()=>document.querySelectorAll('p,span,a,button,label,li,td,th,h1,h2,h3,h4,h5,h6').forEach(e=>{ if(!visible(e)||(e.textContent||'').trim()==='')return;
            const s=getComputedStyle(e), fg=color(s.color), back=bg(e); if(!fg||fg[3]<.99||s.backgroundImage!=='none')return;
            const ratio=(Math.max(lum(fg),lum(back))+.05)/(Math.min(lum(fg),lum(back))+.05), size=parseFloat(s.fontSize), bold=parseInt(s.fontWeight)>=700;
            const threshold=(size>=24||(bold&&size>=18.66))?3:4.5; if(ratio+0.01<threshold) add('R006','MEDIUM','文字对比度不足',`对比度 ${ratio.toFixed(2)}:1，低于 ${threshold}:1。`,e,'SUGGESTION',{foreground:s.color,background:getComputedStyle(e).backgroundColor,ratio:+ratio.toFixed(2),threshold});
          }));

          run('R007',()=>document.querySelectorAll('a[href],button,input,select,textarea,[role=button],[role=link],[tabindex],[onclick]').forEach(e=>{ if(!visible(e))return; const tabindex=e.getAttribute('tabindex'), native=['A','BUTTON','INPUT','SELECT','TEXTAREA'].includes(e.tagName);
            if((!native&&(e.hasAttribute('onclick')||e.hasAttribute('role'))&&tabindex===null)||tabindex==='-1') add('R007','HIGH','交互元素无法通过 Tab 聚焦','可见交互元素不在正常键盘焦点顺序中。',e,'NONE');
            else { e.focus({preventScroll:true}); const s=getComputedStyle(e);
              if(document.activeElement!==e || (s.outlineStyle==='none' && (s.boxShadow==='none'||!s.boxShadow))) add('R007','LOW','焦点样式可能不可见','元素获得焦点时缺少可识别的轮廓或阴影。',e,'TEMPLATE_PATCH');
            }
          }));
          document.activeElement?.blur();

          run('R008',()=>{ const ids=new Map(); document.querySelectorAll('[id]').forEach(e=>{const id=e.id;if(!ids.has(id))ids.set(id,[]);ids.get(id).push(e)});
          ids.forEach((items,id)=>{if(id&&items.length>1)items.slice(1).forEach(e=>add('R008','MEDIUM','页面存在重复 ID',`ID “${id}” 在页面中出现多次。`,e,'NONE',{id,count:items.length}))});
          document.querySelectorAll('[aria-labelledby],[aria-describedby],[aria-controls],[aria-owns]').forEach(e=>{
            ['aria-labelledby','aria-describedby','aria-controls','aria-owns'].forEach(attr=>{ if(!e.hasAttribute(attr))return; const missing=e.getAttribute(attr).split(/\\s+/).filter(id=>id&&!document.getElementById(id)); if(missing.length)add('R008','MEDIUM','ARIA 引用目标不存在',`${attr} 引用了不存在的 ID。`,e,'NONE',{attribute:attr,missing}); });
          }); });
          return {findings:out,ruleErrors};
        }
        """;
}
