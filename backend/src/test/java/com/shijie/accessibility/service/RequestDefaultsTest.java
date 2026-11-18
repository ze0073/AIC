package com.shijie.accessibility.service;

import com.shijie.accessibility.api.PatchController;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class RequestDefaultsTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void omittedOptionalScanFlagsPreserveOfflineDefaults() {
        var request=json.readValue("{\"pages\":[],\"rules\":[]}",ScanService.ScanRequest.class);
        assertThat(request.enableAi()).isFalse();assertThat(request.aiConsent()).isFalse();assertThat(request.enableKeyboardTest()).isTrue();
    }
    @Test void languageOnlyPatchDoesNotRequireAiFields() {
        var request=json.readValue("{\"language\":\"zh-CN\"}",PatchController.GeneratePatch.class);
        assertThat(request.decorative()).isFalse();
        assertThat(json.readValue("{}",PatchController.ApplyPatch.class).reviewAccepted()).isFalse();
    }
}
