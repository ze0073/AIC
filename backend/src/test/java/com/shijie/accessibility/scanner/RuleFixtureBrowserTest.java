package com.shijie.accessibility.scanner;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "playwright.tests", matches = "true")
class RuleFixtureBrowserTest {
    @Test
    void everyDeclaredFixtureHasTheExpectedBrowserOutcome() throws Exception {
        String source;
        try (var stream = getClass().getResourceAsStream("/rules/fixtures.html")) {
            assertThat(stream).isNotNull();
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var fixtures = Jsoup.parse(source);
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                     .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(900, 700))) {
            for (int index = 1; index <= 8; index++) {
                String rule = "R00" + index;
                for (String expected : List.of("hit", "pass")) {
                    for (Element fixture : fixtures.select("template[data-rule=" + rule + "][data-expect=" + expected + "]")) {
                        String lang = "zh-CN";
                        String body = rule.equals("R005") ? fixture.html() : "<h1>夹具页</h1>" + fixture.html();
                        if (rule.equals("R006") && fixture.html().stripLeading().startsWith("<td")) {
                            body = "<h1>夹具页</h1><table><tbody><tr>" + fixture.html() + "</tr></tbody></table>";
                        }
                        if (rule.equals("R004")) {
                            Element marker = fixture.selectFirst("meta[data-document-lang]");
                            lang = marker == null ? "" : marker.attr("data-document-lang");
                            body = "<h1>夹具页</h1>";
                        }
                        page.setContent("<!doctype html><html lang=\"" + escapeAttribute(lang) + "\"><head><meta charset=\"utf-8\"><style>img,input,select,textarea,button,a,[role=button],[role=link],[onclick]{min-width:24px;min-height:24px}a,[role=button],[role=link]{display:inline-block}</style></head><body>" + body + "</body></html>");
                        List<Map<String,Object>> results = findings(page);
                        long matches = results.stream().filter(item -> rule.equals(item.get("ruleId"))).count();
                        String description = rule + " " + expected + " case " + fixture.attr("data-case");
                        if (expected.equals("hit")) assertThat(matches).as(description).isGreaterThan(0);
                        else assertThat(matches).as(description).isZero();
                    }
                }
            }
            page.setContent("<!doctype html><html lang=\"zh-CN\"><head><style>button:focus{outline:none;box-shadow:none}</style></head><body><h1>焦点测试</h1><button>操作</button></body></html>");
            assertThat(ruleCount(page, "R007")).as("removed focus style must be detected while focused").isGreaterThan(0);
            page.setContent("<!doctype html><html lang=\"zh-CN\"><head><style>button:focus{outline:none;box-shadow:none}:focus-visible{outline:3px solid #0b5fff!important;outline-offset:2px!important}</style></head><body><h1>焦点测试</h1><button>操作</button></body></html>");
            assertThat(ruleCount(page, "R007")).as("focus-visible template must clear the finding").isZero();

            page.setContent("<!doctype html><html><body><input style=\"width:40px;height:20px\"></body></html>");
            page.evaluate("""
                    () => { const original=document.querySelectorAll.bind(document);
                      document.querySelectorAll=(selector)=>{if(selector==='img')throw new Error('injected R001 failure');return original(selector)}; }
                    """);
            @SuppressWarnings("unchecked")
            Map<String,Object> partial = (Map<String,Object>) page.evaluate(BrowserScanner.RULE_SCRIPT);
            assertThat((List<String>) partial.get("ruleErrors")).anyMatch(error -> error.startsWith("R001："));
            assertThat((List<Map<String,Object>>) partial.get("findings"))
                    .extracting(item -> item.get("ruleId")).contains("R002", "R004");
        }
    }

    private long ruleCount(Page page, String rule) {
        return findings(page).stream().filter(item -> rule.equals(item.get("ruleId"))).count();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> findings(Page page) {
        Map<String,Object> output = (Map<String,Object>) page.evaluate(BrowserScanner.RULE_SCRIPT);
        assertThat((List<String>) output.get("ruleErrors")).isEmpty();
        return (List<Map<String,Object>>) output.get("findings");
    }

    private String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
