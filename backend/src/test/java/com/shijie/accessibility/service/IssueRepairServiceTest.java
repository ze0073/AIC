package com.shijie.accessibility.service;

import com.shijie.accessibility.ai.AiProvider;
import com.shijie.accessibility.common.*;
import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.mapper.*;
import com.shijie.accessibility.model.*;
import com.shijie.accessibility.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IssueRepairServiceTest {
    @TempDir Path temp;
    final String projectId="0000000000000000000000000A", versionId="0000000000000000000000000B", issueId="issue";
    final String before="<!doctype html><html><head><title>示例</title></head><body>hello</body></html>";
    ProjectService projects; ProjectVersionMapper versions; IssueRepairMapper repairs; StorageService storage;
    ProjectAccessService access; CollaborationService collaboration; AiProvider ai; ScanService scans; TestRunService runs;
    IssueRepairService service; ProjectVersionEntity base;
    Map<String,IssueRepairEntity> saved;

    @BeforeEach void setup() {
        projects=mock(ProjectService.class); versions=mock(ProjectVersionMapper.class); repairs=mock(IssueRepairMapper.class);
        access=mock(ProjectAccessService.class); collaboration=mock(CollaborationService.class); ai=mock(AiProvider.class);
        scans=mock(ScanService.class); runs=mock(TestRunService.class);
        storage=new StorageService(new AppProperties(temp,new AppProperties.Scanner(10,10000,60000),AppProperties.Ai.defaults(false)));
        var imported=storage.importRepairUpload(new MockMultipartFile("file","index.html","text/html",before.getBytes(java.nio.charset.StandardCharsets.UTF_8)),projectId,versionId);
        base=new ProjectVersionEntity(); base.id=versionId; base.projectId=projectId; base.versionNo=1;
        base.entryPage="index.html"; base.manifestHash=imported.manifestHash(); base.sourceType="REPAIR_SOURCE";
        when(projects.version(versionId)).thenReturn(base); when(projects.versions(projectId)).thenReturn(List.of(base));
        when(collaboration.issue(issueId)).thenReturn(new LinkedHashMap<>(Map.of("id",issueId,"projectId",projectId,"runId","original",
                "moduleId","module","taskStatus","OPEN","source","RULE","evidence",Map.of("finding",Map.of("ruleId","R004")))));
        when(access.currentUser()).thenReturn(new AuthService.UserView("user","user","成员",true,LocalDateTime.now()));
        saved=new HashMap<>();
        when(repairs.insert(any(IssueRepairEntity.class))).thenAnswer(call->{var r=(IssueRepairEntity)call.getArgument(0);saved.put(r.id,r);return 1;});
        when(repairs.selectById(anyString())).thenAnswer(call->saved.get(call.getArgument(0)));
        var scan=new ScanTaskEntity(); scan.id="scan"; scan.status="PENDING";
        when(scans.createForVersion(any(),any())).thenReturn(scan); when(scans.get("scan")).thenReturn(scan);
        when(versions.insert(any(ProjectVersionEntity.class))).thenAnswer(call->{var v=(ProjectVersionEntity)call.getArgument(0);when(projects.version(v.id)).thenReturn(v);return 1;});
        var idempotency=mock(IdempotencyService.class); when(idempotency.find(anyString(),anyString())).thenReturn(Optional.empty());
        service=new IssueRepairService(collaboration,access,projects,versions,repairs,storage,
                new FixService(null,null,null,null,null,null,storage,null,new SourcePatchService(),new ObjectMapper()),
                new SourcePatchService(),ai,scans,runs,idempotency,mock(JdbcTemplate.class),new ObjectMapper());
    }

    @Test void writesActualNewVersionKeepsOriginalAndDownloadContainsModifiedCode() throws Exception {
        var source=service.file(issueId,versionId,"index.html");
        var draft=service.generate(issueId,new IssueRepairService.GenerateRequest(versionId,"index.html",source.sha(),"TEMPLATE",null,"zh-CN",null,false,null,null,null,null),"key");
        assertThat(draft.diffText()).contains("+", "lang=\"zh-CN\"");
        assertThat(draft.explanation().get("prevention")).isNotNull();
        assertThatThrownBy(()->service.apply(draft.id(),false)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("REPAIR_REVIEW_REQUIRED"));
        var applied=service.apply(draft.id(),true);
        assertThat(Files.readString(storage.resolveVersionFile(projectId,versionId,"index.html"))).isEqualTo(before);
        assertThat(Files.readString(storage.resolveVersionFile(projectId,applied.newVersionId(),"index.html"))).contains("lang=\"zh-CN\"");
        assertThat(service.apply(draft.id(),true).newVersionId()).isEqualTo(applied.newVersionId());
        verify(versions,times(1)).insert(any(ProjectVersionEntity.class));
        verify(projects,never()).rollback(anyString());
        var bytes=new ByteArrayOutputStream();service.download(draft.id(),bytes);
        try(var zip=new ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))){
            assertThat(zip.getNextEntry().getName()).isEqualTo("index.html");
            assertThat(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).contains("lang=\"zh-CN\"");
        }
    }

    @Test void blocksCrossProjectSourceAndChangedChecksum() {
        base.projectId="0000000000000000000000000C";
        assertThatThrownBy(()->service.file(issueId,versionId,"index.html")).isInstanceOf(ApiException.class);
        base.projectId=projectId;
        assertThatThrownBy(()->service.generate(issueId,new IssueRepairService.GenerateRequest(versionId,"index.html","wrong","MANUAL",before+"\n",null,null,false,null,null,null,null),"key"))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SOURCE_CHANGED"));
        verify(repairs,never()).insert(any(IssueRepairEntity.class));
    }

    @Test void refusesApplyWhenAnotherFileInSourceWasChanged() throws Exception {
        var source=service.file(issueId,versionId,"index.html");
        var draft=service.generate(issueId,new IssueRepairService.GenerateRequest(versionId,"index.html",source.sha(),"MANUAL",before+"\n<!-- corrected -->",null,null,false,null,null,null,null),"key");
        Files.writeString(storage.versionSource(projectId,versionId).resolve("extra.js"),"changed");
        assertThatThrownBy(()->service.apply(draft.id(),true)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SOURCE_CHANGED"));
        verify(versions,never()).insert(any(ProjectVersionEntity.class));
    }

    @Test void respectsMemberPermissionAndAiConsent() {
        doThrow(ApiException.forbidden("无权修改")).when(access).require(projectId,"MEMBER");
        assertThatThrownBy(()->service.generate(issueId,new IssueRepairService.GenerateRequest(versionId,"index.html","sha","MANUAL","new",null,null,false,null,null,null,null),"key")).isInstanceOf(ApiException.class);
        verify(repairs,never()).insert(any(IssueRepairEntity.class));
        reset(access);
        var file=service.file(issueId,versionId,"index.html");
        assertThatThrownBy(()->service.candidate(issueId,new IssueRepairService.CandidateRequest(versionId,"index.html",file.sha(),false)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("AI_CONSENT_REQUIRED"));
        verify(ai,never()).generateRepair(any());
    }

    @Test void rejectsRemovedCoverageAndAcceptsSameReadOnlyCoverage() {
        var json=new ObjectMapper();
        String original=json.writeValueAsString(Map.of("suite",Map.of("definition",Map.of("pages",List.of("/"),"flowSteps",List.of(Map.of("action","click"))))));
        String narrowed=json.writeValueAsString(Map.of("agentSafe",true,"suite",Map.of("definition",Map.of("pages",List.of("/"),"flowSteps",List.of()))));
        assertThatThrownBy(()->service.requireSameCoverage(original,narrowed,"FRONTEND","RULE"))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("RETEST_COVERAGE_CHANGED"));
        String same=json.writeValueAsString(Map.of("suite",Map.of("definition",Map.of("pages",List.of("/"),"flowSteps",List.of()))));
        service.requireSameCoverage(same,narrowed,"FRONTEND","RULE");
        assertThatThrownBy(()->service.requireSameCoverage(same,narrowed,"FRONTEND","FLOW")).isInstanceOf(ApiException.class);
    }

    @Test void refusesEmptyOrUnchangedRepairContent() {
        assertThatThrownBy(()->IssueRepairService.validateContent("same","same")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->IssueRepairService.validateContent("before","")).isInstanceOf(ApiException.class);
    }

    @Test void writesConfirmedImageDescriptionUsingOriginalSelector() throws Exception {
        Files.writeString(storage.resolveVersionFile(projectId,versionId,"index.html"),"<html><body><img id=\"hero\" src=\"banner.png\"></body></html>");
        when(collaboration.issue(issueId)).thenReturn(new LinkedHashMap<>(Map.of("id",issueId,"projectId",projectId,"taskStatus","OPEN",
                "evidence",Map.of("finding",Map.of("ruleId","R001","selector","#hero")))));
        var file=service.file(issueId,versionId,"index.html");
        var draft=service.generate(issueId,new IssueRepairService.GenerateRequest(versionId,"index.html",file.sha(),"TEMPLATE",null,null,"校园入口",false,null,null,null,null),"key");
        assertThat(draft.content()).contains("alt=\"校园入口\"");
    }

    @Test void refusesSendingPossibleSecretToAi() throws Exception {
        Files.writeString(storage.resolveVersionFile(projectId,versionId,"index.html"),"<html><script>const apiKey = 'private-key';</script></html>");
        when(ai.enabled()).thenReturn(true);
        var file=service.file(issueId,versionId,"index.html");
        assertThatThrownBy(()->service.candidate(issueId,new IssueRepairService.CandidateRequest(versionId,"index.html",file.sha(),true)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("AI_SOURCE_SENSITIVE"));
        verify(ai,never()).generateRepair(any());
    }

    @Test void rejectsErrorPageEvenWhenOriginalRuleDisappears() {
        String evidence=new ObjectMapper().writeValueAsString(Map.of("pageUrl","http://site/page","response",Map.of("url","http://site/page","status",404)));
        var issue=new TestRunService.IssueView("failed","module","BROWSER","MEDIUM","CONFIRMED","PENDING_REVIEW","OPEN","页面资源返回错误状态","404",evidence,LocalDateTime.now());
        var run=new TestRunService.RunView("run","project","target","suite","COMPLETED",false,"{}","{}",null,LocalDateTime.now(),null,null,List.of(),List.of(issue));
        assertThatThrownBy(()->service.requireUsablePageEvidence(run)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("RETEST_PAGE_UNAVAILABLE"));
    }
}
