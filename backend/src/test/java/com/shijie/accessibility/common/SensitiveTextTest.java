package com.shijie.accessibility.common;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SensitiveTextTest {
    @Test void masksWholeAuthorizationAndCookieValuesBeforeTruncation() {
        String source="Authorization: Bearer private-bearer\nCookie: sid=private-cookie; csrf=private-csrf\n检查页面";
        assertThat(SensitiveText.redact(source, 1000)).doesNotContain("private-").contains("检查页面");
        assertThat(SensitiveText.redact("prefix Authorization: Bearer private-bearer",25)).doesNotContain("private");
    }

    @Test void masksQuotedJsonAndQueryParametersAndBareBearer() {
        String source="{\"access_token\":\"secret with spaces\",\"count\":3} /api?token=query-secret&ok=1 Bearer bare-secret";
        assertThat(SensitiveText.redact(source,1000)).doesNotContain("secret", "bare-")
                .contains("\"count\":3", "&ok=1", "<redacted>");
    }

    @Test void preservesStructuredMeasurementsAndDoesNotRewriteLegacyObservations() {
        Map<String,Object> source=Map.of("cases",List.of(Map.of("title","api-key=private-value")),"p95Ms",45.5,"count",2);
        Object safe=SensitiveText.redactValues(source);
        assertThat(safe.toString()).doesNotContain("private-value").contains("45.5", "count=2");
        assertThat(source.toString()).contains("private-value");
    }
}
