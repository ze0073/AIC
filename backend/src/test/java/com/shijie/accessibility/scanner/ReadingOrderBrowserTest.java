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
class ReadingOrderBrowserTest {
    @Test
    void evaluatesTenRiskAndTenNonRiskLayouts() throws Exception {
        String source;
        try (var stream = getClass().getResourceAsStream("/rules/r009-fixtures.html")) {
            assertThat(stream).isNotNull();
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var fixtures = Jsoup.parse(source);
        assertThat(fixtures.select("template[data-expect=hit]")).hasSize(10);
        assertThat(fixtures.select("template[data-expect=pass]")).hasSize(10);
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                     .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(900, 700))) {
            for (String expected : List.of("hit", "pass")) {
                for (Element fixture : fixtures.select("template[data-expect=" + expected + "]")) {
                    page.setContent("<!doctype html><html><head><meta charset=\"utf-8\"><style>button,a{min-width:80px;min-height:28px;margin:4px}</style></head><body>"
                            + fixture.html() + "</body></html>");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) page.evaluate(
                            BrowserScanner.READING_ORDER_SCRIPT, Map.of("maxElements", 80));
                    String description = expected + " case " + fixture.attr("data-case") + ": " + result;
                    if ("hit".equals(expected)) assertThat(result.get("status")).as(description).isEqualTo("RISK");
                    else assertThat(result.get("status")).as(description).isNotEqualTo("RISK");
                }
            }
        }
    }
}
