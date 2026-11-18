package com.shijie.accessibility.hosting;
import org.junit.jupiter.api.Test;
import java.util.*;
import com.shijie.accessibility.service.IssueFingerprint;
import com.shijie.accessibility.scanner.OnlineFrontendScanner;
import static org.assertj.core.api.Assertions.*;
class HostedRunIdentityTest {
    Map<String,Object> snapshot(String site,String deployment,String base){return Map.of("hosting",Map.of("siteId",site,"deploymentId",deployment,"baseDeploymentId",base,"sourceManifest","manifest"));}
    @Test void onlyAllowsExplicitRetestLineageWithinSameSite(){assertThat(HostedRunIdentity.isRetest(snapshot("a","old",""),snapshot("a","new","old"))).isTrue();assertThat(HostedRunIdentity.isRetest(snapshot("a","old",""),snapshot("b","new","old"))).isFalse();assertThat(HostedRunIdentity.isRetest(snapshot("a","old",""),snapshot("a","new","unrelated"))).isFalse();assertThat(HostedRunIdentity.isRetest(Map.of(),Map.of())).isFalse();}
    @Test void changingPreviewAddressDoesNotClearUnfixedIssue(){var first=issue("http://first.localhost/page");var second=issue("http://second.localhost/page");assertThat(IssueFingerprint.of("FRONTEND",first,"site")).isEqualTo(IssueFingerprint.of("FRONTEND",second,"site"));assertThat(IssueFingerprint.of("FRONTEND",first)).isNotEqualTo(IssueFingerprint.of("FRONTEND",second));assertThat(IssueFingerprint.of("FRONTEND",first,"other")).isNotEqualTo(IssueFingerprint.of("FRONTEND",first,"site"));}
    OnlineFrontendScanner.IssueDraft issue(String url){return new OnlineFrontendScanner.IssueDraft("R004","RULE","LOW","DETERMINISTIC","缺少语言","说明",Map.of("pageUrl",url,"finding",Map.of("selector","html")));}
}
