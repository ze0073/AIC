package com.shijie.accessibility.service;

import com.shijie.accessibility.model.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ComparisonServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private ScanTaskEntity scan(String status,String pages,String rules,boolean keyboard) {
        var s=new ScanTaskEntity();s.status=status;s.ruleVersion="P0-1.0+P1-0.4";
        s.scanConfig="{\"pages\":"+pages+",\"rules\":"+rules+",\"enableKeyboardTest\":"+keyboard+"}";return s;
    }
    private FindingEntity finding(String selector,String status,String evidence) {
        var f=new FindingEntity();f.id=UUID.randomUUID().toString();f.ruleId="R009";f.pagePath="index.html";
        f.selector=selector;f.status=status;f.evidenceFingerprint=evidence;return f;
    }
    @Test void setsAreOrderIndependentButPartialAndChangedScopeAreNotComparable() {
        var a=scan("COMPLETED","[\"a.html\",\"b.html\"]","[\"R001\",\"R009\"]",true);
        var b=scan("COMPLETED","[\"b.html\",\"a.html\"]","[\"R009\",\"R001\"]",true);
        assertThat(ComparisonService.incompatibilities(a,b,json)).isEmpty();
        b.status="PARTIAL";assertThat(ComparisonService.incompatibilities(a,b,json)).contains("两次扫描必须均完整完成");
        b=scan("COMPLETED","[\"a.html\"]","[\"R001\"]",false);b.ruleVersion="old";
        assertThat(ComparisonService.incompatibilities(a,b,json)).hasSize(4);
    }
    @Test void unresolvedOldScopeDoesNotInventImprovement() {
        var a=scan("COMPLETED","[]","[\"R001\"]",true);
        assertThat(ComparisonService.incompatibilities(a,a,json)).isNotEmpty();
    }
    @Test void explicitResolvedPagesTakePrecedenceOverEmptyRequest() {
        var a=scan("COMPLETED","[\"index.html\"]","[\"R001\"]",true);
        var b=scan("COMPLETED","[]","[\"R001\"]",true);
        b.scanConfig=b.scanConfig.replace("{","{\"resolvedPages\":[\"index.html\"],");
        assertThat(ComparisonService.incompatibilities(a,b,json)).isEmpty();
    }
    @Test void dismissalIsReviewChangeNotCodeFix() {
        var pairs=ComparisonService.pair(List.of(finding("html","NEEDS_REVIEW","a")),List.of(finding("html","DISMISSED","a")));
        assertThat(pairs).hasSize(1);assertThat(pairs.getFirst().kind()).isEqualTo("REMAINING");
        assertThat(pairs.getFirst().reviewChanged()).isTrue();assertThat(pairs.getFirst().evidenceChanged()).isFalse();
    }
    @Test void sourceChangeIsFlaggedButRenamedSelectorIsNotGuessed() {
        var pairs=ComparisonService.pair(List.of(finding("#a","PENDING","a"),finding("#b","PENDING","b")),
                List.of(finding("#a","PENDING","c"),finding("#renamed","PENDING","b")));
        assertThat(pairs).extracting(ComparisonService.Pair::kind).containsExactly("REMAINING","NOT_REDETECTED","INTRODUCED");
        assertThat(pairs.getFirst().evidenceChanged()).isTrue();
    }
}
