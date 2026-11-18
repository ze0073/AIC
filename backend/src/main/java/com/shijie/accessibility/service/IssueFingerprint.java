package com.shijie.accessibility.service;

import com.shijie.accessibility.common.Hashes;
import com.shijie.accessibility.scanner.OnlineFrontendScanner;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Stable case/location identity; evidence values and screenshots are deliberately excluded. */
public final class IssueFingerprint {
    private IssueFingerprint() { }

    public static String of(String module, OnlineFrontendScanner.IssueDraft draft) {
        return of(module,draft,null);
    }
    public static String of(String module, OnlineFrontendScanner.IssueDraft draft,String hostedSite) {
        Map<String,Object> evidence = draft.evidence();
        Object finding = evidence.get("finding");
        Map<?,?> detail = finding instanceof Map<?,?> map ? map : Map.of();
        String location = Objects.toString(evidence.getOrDefault("pageUrl", evidence.getOrDefault("url", "")));
        try {
            URI uri = URI.create(location);
            location = hostedSite==null?uri.getScheme()+"://"+uri.getAuthority()+uri.getPath():"site:"+hostedSite+uri.getPath();
        } catch (Exception ignored) { }
        String identity = String.join("|", module, draft.key(), draft.source(), location,
                Objects.toString(evidence.get("name"), ""), Objects.toString(evidence.get("step"), ""),
                Objects.toString(evidence.get("selector"), ""), Objects.toString(evidence.get("parameter"), ""),
                Objects.toString(detail.get("selector"), ""), Objects.toString(detail.get("path"), ""),
                Objects.toString(detail.get("element"), ""));
        return Hashes.sha256(identity);
    }
}
