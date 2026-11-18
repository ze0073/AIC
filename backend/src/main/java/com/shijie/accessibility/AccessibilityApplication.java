package com.shijie.accessibility;

import com.shijie.accessibility.config.AppProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@MapperScan("com.shijie.accessibility.mapper")
@EnableConfigurationProperties(AppProperties.class)
public class AccessibilityApplication {
    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--runtime-check")) {
            try (var playwright = com.microsoft.playwright.Playwright.create();
                 var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true))) {
                var page = browser.newPage();
                page.setContent("<html lang=\"zh-CN\"><title>ready</title></html>");
                if (!"ready".equals(page.title())) throw new IllegalStateException("浏览器检查失败");
                System.out.println("Chromium 已就绪");
            }
            return;
        }
        SpringApplication.run(AccessibilityApplication.class, args);
    }

}
