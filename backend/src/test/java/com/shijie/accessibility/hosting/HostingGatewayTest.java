package com.shijie.accessibility.hosting;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HostingGatewayTest {
    @Test void stripsManagementAndPreviewCookiesWhilePreservingWebsiteSessions() {
        assertThat(HostingGateway.applicationCookies("SHIJIE_PLATFORM_SESSION=admin; JSESSIONID=website; __sj_preview=grant; SHIJIE_PLATFORM_XSRF=csrf; cart=123"))
                .isEqualTo("JSESSIONID=website; cart=123");
        assertThat(HostingGateway.applicationCookies(null)).isEmpty();
        assertThat(HostingGateway.applicationCookies("__sj_preview=grant")).isEmpty();
    }
}
