package com.shijie.accessibility.online;

import com.shijie.accessibility.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetScopeServiceTest {
    private final TargetScopeService scope = new TargetScopeService();

    @Test
    void registrationPinsEveryCurrentDnsAddressAndNormalizesPath() {
        var preview = scope.previewRegistration("http://localhost:8080/test/app");
        var result = scope.validateRegistration(preview.baseUrl(), preview.addresses(), List.of("/test"));

        assertThat(result.origin()).isEqualTo("http://localhost:8080");
        assertThat(result.pathPrefixes()).containsExactly("/test/");
        assertThat(result.addresses()).containsExactlyInAnyOrderElementsOf(preview.addresses());
        assertThatThrownBy(()->scope.validateRegistration(preview.baseUrl(),java.util.stream.Stream.concat(preview.addresses().stream(),java.util.stream.Stream.of("192.0.2.1")).toList(),List.of("/test")))
                .isInstanceOf(ApiException.class).hasMessageContaining("DNS");
    }

    @Test
    void blocksCrossOriginRedirectAndPathEscape() {
        var preview = scope.previewRegistration("http://localhost:8080/test/");
        var target = new OnlineTargetService.TargetView("target", "project", "local", preview.baseUrl(), preview.origin(),
                preview.addresses(), List.of("/test/"), false, false, null, LocalDateTime.now(), LocalDateTime.now());

        assertThatThrownBy(() -> scope.requireAllowed(target, "http://localhost:8080/admin"))
                .isInstanceOf(ApiException.class).hasMessageContaining("路径");
        assertThatThrownBy(() -> scope.requireAllowed(target, "http://127.0.0.1:8080/test/"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Origin");
    }

    @Test
    void rejectsUrlsContainingUserInfo() {
        assertThatThrownBy(() -> scope.previewRegistration("http://user:password@localhost:8080/"))
                .isInstanceOf(ApiException.class).hasMessageContaining("用户信息");
    }

    @Test
    void rejectsEncodedPathTraversalBeforeServerCanDecodeIt() {
        var preview=scope.previewRegistration("http://localhost:8080/test/");
        var target=new OnlineTargetService.TargetView("target","project","local",preview.baseUrl(),preview.origin(),
                preview.addresses(),List.of("/test/"),false,false,null,LocalDateTime.now(),LocalDateTime.now());
        assertThatThrownBy(()->scope.requireAllowed(target,"http://localhost:8080/test/%2e%2e/admin"))
                .isInstanceOf(ApiException.class);
    }

    @Test void refusesNonLiteralPinnedAddressEvenForLegacyTargets(){
        var preview=scope.previewRegistration("http://localhost:8080/test/");
        var target=new OnlineTargetService.TargetView("target","project","legacy",preview.baseUrl(),preview.origin(),
                java.util.stream.Stream.concat(java.util.stream.Stream.of("attacker.example"),preview.addresses().stream()).toList(),List.of("/test/"),false,false,null,LocalDateTime.now(),LocalDateTime.now());
        assertThatThrownBy(()->scope.pinnedAddress(target)).isInstanceOf(ApiException.class).hasMessageContaining("IP");
    }

    @Test void stopsBeforeRequestWhenDnsChangesOutsideRegisteredAddresses(){
        AtomicReference<Set<String>> answer=new AtomicReference<>(Set.of("127.0.0.1"));
        TargetScopeService changing=new TargetScopeService(){@Override Set<String> resolve(String host){return answer.get();}};
        var registered=changing.validateRegistration("http://quality.test:8091/api/",List.of("127.0.0.1"),List.of("/api/"));
        var target=new OnlineTargetService.TargetView("target","project","local",registered.baseUrl(),registered.origin(),
                registered.addresses(),registered.pathPrefixes(),false,false,null,LocalDateTime.now(),LocalDateTime.now());
        changing.requireAllowed(target,"http://quality.test:8091/api/health");
        answer.set(Set.of("203.0.113.55"));
        assertThatThrownBy(()->changing.requireAllowed(target,"http://quality.test:8091/api/health"))
                .isInstanceOf(ApiException.class).hasMessageContaining("DNS");
    }
}
