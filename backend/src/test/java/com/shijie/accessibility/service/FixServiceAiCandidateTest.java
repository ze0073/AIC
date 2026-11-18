package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.mapper.EvidenceMapper;
import com.shijie.accessibility.mapper.FindingMapper;
import com.shijie.accessibility.mapper.FixPatchMapper;
import com.shijie.accessibility.mapper.ProjectMapper;
import com.shijie.accessibility.mapper.ProjectVersionMapper;
import com.shijie.accessibility.mapper.ScanTaskMapper;
import com.shijie.accessibility.model.EvidenceEntity;
import com.shijie.accessibility.model.FindingEntity;
import com.shijie.accessibility.model.FixPatchEntity;
import com.shijie.accessibility.model.ProjectVersionEntity;
import com.shijie.accessibility.model.ScanTaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FixServiceAiCandidateTest {
    private static final String PROJECT = id('A');
    private static final String VERSION = id('B');
    private static final String SCAN = id('C');
    private static final String FINDING = id('D');
    private static final String EVIDENCE = id('E');

    @TempDir Path temp;

    private FixPatchMapper patches;
    private FindingMapper findings;
    private ScanTaskMapper scans;
    private ProjectVersionMapper versions;
    private EvidenceMapper evidences;
    private StorageService storage;
    private FixService service;
    private final AtomicReference<FixPatchEntity> inserted = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        patches = mock(FixPatchMapper.class);
        findings = mock(FindingMapper.class);
        scans = mock(ScanTaskMapper.class);
        versions = mock(ProjectVersionMapper.class);
        evidences = mock(EvidenceMapper.class);
        storage = new StorageService(new AppProperties(temp,
                new AppProperties.Scanner(10, 1_000, 10_000), AppProperties.Ai.defaults(false)));
        service = new FixService(patches, findings, scans, versions, mock(ProjectMapper.class), evidences,
                storage, mock(ScanService.class), new SourcePatchService(), new ObjectMapper());

        FindingEntity finding = new FindingEntity();
        finding.id = FINDING;
        finding.scanTaskId = SCAN;
        finding.ruleId = "R001";
        finding.fixMode = "SUGGESTION";
        finding.pagePath = "index.html";
        finding.selector = "#hero";
        when(findings.selectById(FINDING)).thenReturn(finding);

        ScanTaskEntity scan = new ScanTaskEntity();
        scan.id = SCAN;
        scan.versionId = VERSION;
        when(scans.selectById(SCAN)).thenReturn(scan);

        ProjectVersionEntity version = new ProjectVersionEntity();
        version.id = VERSION;
        version.projectId = PROJECT;
        version.entryPage = "index.html";
        when(versions.selectById(VERSION)).thenReturn(version);

        EvidenceEntity evidence = new EvidenceEntity();
        evidence.id = EVIDENCE;
        evidence.findingId = FINDING;
        evidence.kind = "AI";
        evidence.payload = "{\"confidenceBand\":\"high\",\"provider\":\"qwen\",\"model\":\"qwen3-vl-plus\"}";
        when(evidences.selectById(EVIDENCE)).thenReturn(evidence);
        when(patches.insert(any(FixPatchEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });

        Path source = storage.versionSource(PROJECT, VERSION);
        Files.createDirectories(source);
        Files.writeString(source.resolve("index.html"),
                "<!doctype html><html><body><img id='hero' src='hero.png'></body></html>",
                StandardCharsets.UTF_8);
    }

    @Test
    void createsAuditableAiCandidateWithoutRewritingOtherSourceBytes() throws Exception {
        FixPatchEntity patch = service.generate(FINDING,
                new FixService.PatchRequest(null, EVIDENCE, "  校园\u0007  全景  ", false));

        assertThat(patch).isSameAs(inserted.get());
        assertThat(patch.patchType).isEqualTo("AI_CANDIDATE");
        assertThat(patch.candidateText).isEqualTo("校园 全景");
        assertThat(patch.decorative).isFalse();
        assertThat(patch.sourceEvidenceId).isEqualTo(EVIDENCE);
        assertThat(patch.sourceProvider).isEqualTo("qwen");
        assertThat(patch.sourceModel).isEqualTo("qwen3-vl-plus");
        assertThat(patch.patchedContent).isEqualTo(
                "<!doctype html><html><body><img id='hero' src='hero.png' alt=\"校园 全景\"></body></html>");
        assertThat(patch.diffText).contains("alt=\"校园 全景\"");
    }

    @Test
    void rejectsForeignOrLowConfidenceEvidence() {
        EvidenceEntity foreign = new EvidenceEntity();
        foreign.id = EVIDENCE;
        foreign.findingId = id('F');
        foreign.kind = "AI";
        foreign.payload = "{\"confidenceBand\":\"high\"}";
        when(evidences.selectById(EVIDENCE)).thenReturn(foreign);
        assertCode("AI_EVIDENCE_INVALID", () -> service.generate(FINDING,
                new FixService.PatchRequest(null, EVIDENCE, "说明", false)));

        foreign.findingId = FINDING;
        foreign.payload = "{\"confidenceBand\":\"low\"}";
        assertCode("AI_CONFIDENCE_TOO_LOW", () -> service.generate(FINDING,
                new FixService.PatchRequest(null, EVIDENCE, "说明", false)));
    }

    @Test
    void returnsSamePendingCandidateButConflictsForDifferentContent() throws Exception {
        FixPatchEntity pending = new FixPatchEntity();
        pending.id = id('P');
        pending.findingId = FINDING;
        pending.status = "PENDING";
        pending.patchType = "AI_CANDIDATE";
        pending.sourceEvidenceId = EVIDENCE;
        pending.candidateText = "校园全景";
        pending.decorative = false;
        when(patches.selectOne(any())).thenReturn(pending);

        assertThat(service.generate(FINDING,
                new FixService.PatchRequest(null, EVIDENCE, "校园全景", false))).isSameAs(pending);
        assertCode("PATCH_ALREADY_PENDING", () -> service.generate(FINDING,
                new FixService.PatchRequest(null, EVIDENCE, "校园入口", false)));
    }

    @Test
    void requiresExplicitHumanAcceptanceBeforeApplyingAiPatch() {
        FixPatchEntity patch = new FixPatchEntity();
        patch.id = id('P');
        patch.patchType = "AI_CANDIDATE";
        patch.status = "PENDING";
        when(patches.selectById(patch.id)).thenReturn(patch);

        assertCode("AI_REVIEW_REQUIRED", () -> service.apply(patch.id, false));
    }

    @Test
    void preservesOriginalPagesRulesAndKeyboardConfigButDisablesAiForRetest() {
        ScanTaskEntity source = new ScanTaskEntity();
        source.scanConfig = "{\"pages\":[\"index.html\",\"about.html\"],\"rules\":[\"R001\",\"R003\"]," +
                "\"enableKeyboardTest\":false,\"enableAi\":true,\"aiConsent\":true}";

        ScanService.ScanRequest request = service.rescanRequest(source);

        assertThat(request.pages()).containsExactly("index.html", "about.html");
        assertThat(request.rules()).containsExactly("R001", "R003");
        assertThat(request.enableKeyboardTest()).isFalse();
        assertThat(request.enableAi()).isFalse();
        assertThat(request.aiConsent()).isFalse();
    }

    @Test
    void legacyRetestWithoutRuleSnapshotDoesNotSilentlyAddR009() {
        ScanTaskEntity source = new ScanTaskEntity();
        source.ruleVersion = "P0-1.0";
        source.scanConfig = "{}";

        ScanService.ScanRequest request = service.rescanRequest(source);

        assertThat(request.rules()).containsExactly("R001", "R002", "R003", "R004", "R005", "R006", "R007", "R008");
    }

    private void assertCode(String expected, ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }

    private static String id(char suffix) {
        return "0000000000000000000000000" + suffix;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
