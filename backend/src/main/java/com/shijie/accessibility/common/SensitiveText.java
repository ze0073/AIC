package com.shijie.accessibility.common;

import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Redacts complete values before shortening text for model input or persisted summaries. */
public final class SensitiveText {
    private static final Pattern HEADERS = Pattern.compile(
            "(?im)\\b(authorization|proxy-authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]+");
    private static final Pattern VALUES = Pattern.compile(
            "(?i)([\"']?(?:[a-z0-9_-]*token|api[-_ .]?key|password|passwd|secret|sessionid|jsessionid|demo_session)[\"']?\\s*[:=]\\s*)"
                    + "(?:\"[^\"]*\"|'[^']*'|[^\\s,;&}\"'<>]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[a-z0-9._~+/-]+=*");
    private static final Pattern JWT = Pattern.compile("\\beyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\b");

    private SensitiveText() { }

    public static String redact(Object value, int max) {
        String text = value == null ? "" : String.valueOf(value).strip();
        text = HEADERS.matcher(text).replaceAll("$1=<redacted>");
        text = VALUES.matcher(text).replaceAll("$1<redacted>");
        text = BEARER.matcher(text).replaceAll("Bearer <redacted>");
        text = JWT.matcher(text).replaceAll("<redacted>");
        return text.length() <= max ? text : text.substring(0, max);
    }

    public static Object redactValues(Object value) {
        if (value instanceof String text) return redact(text, 1000);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> safe.put(String.valueOf(key), redactValues(item)));
            return safe;
        }
        if (value instanceof List<?> list) return list.stream().map(SensitiveText::redactValues).toList();
        return value;
    }
}
