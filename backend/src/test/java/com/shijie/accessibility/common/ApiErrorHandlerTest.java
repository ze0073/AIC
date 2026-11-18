package com.shijie.accessibility.common;

import com.shijie.accessibility.ai.AiProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorHandlerTest {
    @Test void modelFailureIsExplicitAndDoesNotExposeProviderDetails() {
        var request=new MockHttpServletRequest();
        request.setAttribute("traceId","trace-1");
        var response=new ApiErrorHandler().handleAiUnavailable(
                new AiProviderException("private endpoint or credential detail"),request);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        Map<?,?> body=(Map<?,?>)response.getBody();
        assertThat(body.get("code")).isEqualTo("AI_SERVICE_UNAVAILABLE");
        assertThat(body.get("message").toString()).doesNotContain("private endpoint or credential detail");
    }
}
