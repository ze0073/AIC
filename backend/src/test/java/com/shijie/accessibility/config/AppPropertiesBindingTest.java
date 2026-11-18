package com.shijie.accessibility.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesBindingTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "app.storage-root=./data",
                    "app.scanner.max-pages=10",
                    "app.scanner.page-timeout-ms=10000",
                    "app.scanner.total-timeout-ms=60000",
                    "app.ai.enabled=true",
                    "app.ai.provider=qwen",
                    "app.ai.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "app.ai.api-key=test-key",
                    "app.ai.model=qwen3-vl-plus",
                    "app.ai.timeout-ms=20000",
                    "app.ai.max-retries=1");

    @Test
    void bindsNestedAiConfigurationUsingTheRecordConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AppProperties properties = context.getBean(AppProperties.class);
            assertThat(properties.ai().enabled()).isTrue();
            assertThat(properties.ai().model()).isEqualTo("qwen3-vl-plus");
            assertThat(properties.ai().apiKey()).isEqualTo("test-key");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfiguration {}
}
