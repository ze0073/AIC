package com.shijie.accessibility.scanner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBrowserMethodTest {
    @Test void browserWritesNeedTheConfirmedInteractionWindow() {
        assertThat(OnlineFrontendScanner.agentMethodAllowed("GET",false)).isTrue();
        assertThat(OnlineFrontendScanner.agentMethodAllowed("post",false)).isFalse();
        assertThat(OnlineFrontendScanner.agentMethodAllowed("DELETE",false)).isFalse();
        assertThat(OnlineFrontendScanner.agentMethodAllowed("POST",true)).isTrue();
    }
}
