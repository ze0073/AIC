package com.shijie.accessibility.scanner;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;

import static org.assertj.core.api.Assertions.assertThat;

class RuleFixtureManifestTest {
    @Test
    void everyP0RuleDeclaresTenPositiveAndNegativeCases() throws Exception {
        try (var stream = getClass().getResourceAsStream("/rules/fixture-manifest.json")) {
            assertThat(stream).isNotNull();
            String manifest = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (int i = 1; i <= 9; i++) {
                assertThat(manifest).contains("\"R00" + i + "\"");
            }
            assertThat(manifest.split("\"hit\": 10", -1)).hasSize(10);
            assertThat(manifest.split("\"pass\": 10", -1)).hasSize(10);
        }
    }

    @Test
    void fixtureSuiteContainsTenExecutableSnippetsForBothOutcomes() throws Exception {
        try (var stream = getClass().getResourceAsStream("/rules/fixtures.html")) {
            assertThat(stream).isNotNull();
            var document = Jsoup.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            for (int i = 1; i <= 8; i++) {
                assertThat(document.select("template[data-rule=R00" + i + "][data-expect=hit]")).hasSize(10);
                assertThat(document.select("template[data-rule=R00" + i + "][data-expect=pass]")).hasSize(10);
            }
        }
    }
}
