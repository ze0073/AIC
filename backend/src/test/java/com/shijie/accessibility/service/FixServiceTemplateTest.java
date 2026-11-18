package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixServiceTemplateTest {
    private final FixService service = new FixService(
            null, null, null, null, null, null, null, null, new SourcePatchService(), null);

    @Test
    void languagePatchReplacesQuotedAndUnquotedInvalidValues() {
        String unquoted = service.patchLanguage("<!doctype html><html lang=123><head></head><body></body></html>", "zh-CN");
        String quoted = service.patchLanguage("<html lang='bad_value'><head></head><body></body></html>", "en-US");
        assertThat(unquoted).contains("<html lang=\"zh-CN\">").doesNotContain("lang=123");
        assertThat(quoted).contains("<html lang=\"en-US\">").doesNotContain("bad_value");
    }

    @Test
    void labelPatchOnlyAssociatesAnExistingNearbyLabel() {
        String patched = service.patchLabel("<html><body><label>姓名</label><input id=\"student\"></body></html>", "#student");
        assertThat(patched).contains("<label for=\"student\">姓名</label>");
        assertThatThrownBy(() -> service.patchLabel("<html><body><input id=\"student\"></body></html>", "#student"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void focusPatchIsIdempotentAndPatchValidationOnlyRejectsNewUnsafeContent() {
        String source = "<html><head></head><body><a href=\"https://existing.invalid\">资料</a><button>操作</button></body></html>";
        String patched = service.patchFocus(source);
        assertThat(patched).contains("data-accessibility-fix=\"focus-visible\"");
        assertThat(service.patchFocus(patched)).isEqualTo(patched);
        assertThatCode(() -> service.validatePatch(source, patched)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validatePatch(source, patched.replace("</body>", "<script>alert(1)</script></body>")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("补丁不得增加");
    }
}
