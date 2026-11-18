package com.shijie.accessibility.scanner;

import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.online.PinnedHttp;
import com.shijie.accessibility.online.TargetScopeService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnlineSecurityScannerTest {
    @Test void missingHttpsCertificateEvidenceIsInconclusiveRatherThanPassing() throws Exception {
        TargetScopeService scope=new TargetScopeService();
        var registered=scope.previewRegistration("https://127.0.0.1/");
        var target=new OnlineTargetService.TargetView("target","project","local",registered.baseUrl(),registered.origin(),
                registered.addresses(),registered.pathPrefixes(),false,false,null,LocalDateTime.now(),LocalDateTime.now());
        PinnedHttp http=mock(PinnedHttp.class);
        when(http.send(eq(target),any(),eq("GET"),anyMap(),isNull(),eq(12))).thenReturn(
                new PinnedHttp.Result(200,Map.of("strict-transport-security",List.of("max-age=3600"),
                        "content-security-policy",List.of("default-src 'self'")),"<html></html>",null));
        var output=new OnlineSecurityScanner(scope,http).inspect(target);
        assertTrue(output.issues().stream().anyMatch(issue->"TRANSPORT_CHECK_INCONCLUSIVE".equals(issue.key())
                &&"INCONCLUSIVE".equals(issue.confidence())));
        assertFalse(output.issues().stream().anyMatch(issue->"HSTS_MISSING".equals(issue.key())));
    }

    @Test void observesHttpToHttpsRedirectWithoutFollowingOrClaimingCertificatePassed() throws Exception {
        TargetScopeService scope=new TargetScopeService();
        var registered=scope.previewRegistration("http://127.0.0.1:8091/");
        var target=new OnlineTargetService.TargetView("target","project","local",registered.baseUrl(),registered.origin(),
                registered.addresses(),registered.pathPrefixes(),false,false,null,LocalDateTime.now(),LocalDateTime.now());
        PinnedHttp http=mock(PinnedHttp.class);
        when(http.send(eq(target),any(),eq("GET"),anyMap(),isNull(),eq(12))).thenReturn(
                new PinnedHttp.Result(301,Map.of("location",List.of("https://example.invalid/login?token=never-report")),"",null));
        var output=new OnlineSecurityScanner(scope,http).inspect(target);
        assertTrue(output.issues().stream().anyMatch(issue->"HTTPS_REDIRECT_UNVERIFIED".equals(issue.key())
                &&"INCONCLUSIVE".equals(issue.confidence())));
        assertFalse(output.issues().stream().anyMatch(issue->"TRANSPORT_HTTP".equals(issue.key())));
        assertFalse(output.evidence().toString().contains("never-report"));
        verify(http,times(1)).send(eq(target),any(),eq("GET"),anyMap(),isNull(),eq(12));
    }
}
