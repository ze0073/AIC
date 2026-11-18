package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourcePatchServiceTest {
    private final SourcePatchService service = new SourcePatchService();

    @Test
    void addsAltToOnlyTheSelectedOpeningTagWithoutSerializingTheDocument() {
        String source = "<!doctype html>\n<html><body><img id='hero' src='a.png'><img src=\"b.png\"></body></html>";

        var result = service.patchAiAttribute(source, "#hero", "R001", " 校园   图书馆 ", false);

        assertThat(result.normalizedText()).isEqualTo("校园 图书馆");
        assertThat(result.content()).isEqualTo(
                "<!doctype html>\n<html><body><img id='hero' src='a.png' alt=\"校园 图书馆\"><img src=\"b.png\"></body></html>");
    }

    @Test
    void escapesAttributeTextAndPreservesSelfClosingSyntax() {
        String source = "<html><body><img id=\"hero\" src='a.png' /></body></html>";

        var result = service.patchAiAttribute(source, "#hero", "R001", "A & \"B\" <入口>", false);

        assertThat(result.content()).contains("alt=\"A &amp; &quot;B&quot; &lt;入口&gt;\"/>");
    }

    @Test
    void supportsExplicitDecorativeImages() {
        var result = service.patchAiAttribute(
                "<html><body><img id='ornament' src='line.svg'></body></html>",
                "#ornament", "R001", "ignored", true);

        assertThat(result.normalizedText()).isEmpty();
        assertThat(result.content()).contains("alt=\"\"");
    }

    @Test
    void addsAriaLabelToButtonsLinksAndRoleElements() {
        assertThat(service.patchAiAttribute("<button id='save'></button>", "#save", "R003", "保存", false).content())
                .contains("aria-label=\"保存\"");
        assertThat(service.patchAiAttribute("<a id='home' href='/'></a>", "#home", "R003", "首页", false).content())
                .contains("aria-label=\"首页\"");
        assertThat(service.patchAiAttribute("<span id='menu' role='button'></span>", "#menu", "R003", "菜单", false).content())
                .contains("aria-label=\"菜单\"");
    }

    @Test
    void rejectsAmbiguousDynamicStaleAndUnsupportedTargets() {
        assertThatThrownBy(() -> service.patchAiAttribute(
                "<img src='a'><img src='b'>", "img", "R001", "图片", false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_ELEMENT_AMBIGUOUS"));
        assertThatThrownBy(() -> service.patchAiAttribute(
                "<div id='root'></div>", "#created-by-script", "R003", "菜单", false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_ELEMENT_NOT_FOUND"));
        assertThatThrownBy(() -> service.patchAiAttribute(
                "<img id='hero' alt='已有文本'>", "#hero", "R001", "新文本", false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("PATCH_TARGET_STALE"));
        assertThatThrownBy(() -> service.patchAiAttribute(
                "<div id='plain'></div>", "#plain", "R003", "名称", false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_ELEMENT_MISMATCH"));
    }

    @Test
    void enforcesCandidateLimitsAndDecorativeRuleBoundaries() {
        assertThatThrownBy(() -> service.patchAiAttribute(
                "<button id='save'></button>", "#save", "R003", " ", false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CANDIDATE_REQUIRED"));
        assertThatThrownBy(() -> service.patchAiAttribute(
                "<button id='save'></button>", "#save", "R003", "x".repeat(121), false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CANDIDATE_TOO_LONG"));
        assertThatThrownBy(() -> service.patchAiAttribute(
                "<button id='save'></button>", "#save", "R003", "保存", true))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DECORATIVE_NOT_ALLOWED"));
    }
}
