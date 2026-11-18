package com.shijie.accessibility.hosting;
import org.junit.jupiter.api.*;import org.springframework.jdbc.core.JdbcTemplate;import tools.jackson.databind.ObjectMapper;import java.util.*;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
class HostingReleaseGateTest {
 JdbcTemplate jdbc;HostingService service;ObjectMapper json=new ObjectMapper();Map<String,Object> deployment,snapshot;
 @BeforeEach void setup(){jdbc=mock(JdbcTemplate.class);service=new HostingService(jdbc,json,null,null,null,null,null,null,null,null,null,null,null,null,null);
 deployment=new HashMap<>(Map.of("id","deployment","site_id","site","source_version_id","source","source_manifest","hash","image_digest","sha256:image","run_id","run"));
 snapshot=new HashMap<>(Map.of("hosting",Map.of("deploymentId","deployment","siteId","site","sourceVersionId","source","sourceManifest","hash","imageDigest","sha256:image"),"modules",List.of("FRONTEND","SECURITY")));
 when(jdbc.queryForList("SELECT status,config_snapshot FROM test_runs WHERE id=?","run")).thenAnswer(i->List.of(Map.of("status","COMPLETED","config_snapshot",json.writeValueAsString(snapshot))));
 when(jdbc.queryForList("SELECT module_type,status,result_payload FROM test_run_modules WHERE run_id=?","run")).thenReturn(List.of(Map.of("module_type","FRONTEND","status","COMPLETED"),Map.of("module_type","SECURITY","status","COMPLETED")));
 when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("run"))).thenReturn(0);
 }
 @Test void acceptsOnlyCompleteEvidenceBoundToTheDeployment(){assertThat(service.passed(deployment)).isTrue();deployment.put("source_manifest","changed");assertThat(service.passed(deployment)).isFalse();}
 @Test void cannotSubstituteAnotherDeploymentOrImage(){snapshot.put("hosting",Map.of("deploymentId","another","siteId","site","sourceVersionId","source","sourceManifest","hash","imageDigest","sha256:image"));assertThat(service.passed(deployment)).isFalse();}
 @Test void missingModuleDoesNotPass(){when(jdbc.queryForList("SELECT module_type,status,result_payload FROM test_run_modules WHERE run_id=?","run")).thenReturn(List.of(Map.of("module_type","FRONTEND","status","COMPLETED")));assertThat(service.passed(deployment)).isFalse();}
 @Test void appliedRepairNeedsItsOwnVerifiedRetest(){deployment.put("repair_id","repair");when(jdbc.queryForObject(contains("FROM issue_repairs"),eq(Integer.class),eq("repair"),eq("run"))).thenReturn(0);assertThat(service.passed(deployment)).isFalse();}
 @Test void inconclusiveEvidenceDoesNotPass(){when(jdbc.queryForObject(contains("evidence_confidence"),eq(Integer.class),eq("run"))).thenReturn(1);assertThat(service.passed(deployment)).isFalse();}
 @Test void missingPerformanceMetricsDoesNotPass(){snapshot.put("modules",List.of("FRONTEND","SECURITY","PERFORMANCE"));when(jdbc.queryForList("SELECT module_type,status,result_payload FROM test_run_modules WHERE run_id=?","run")).thenReturn(List.of(Map.of("module_type","FRONTEND","status","COMPLETED"),Map.of("module_type","SECURITY","status","COMPLETED"),Map.of("module_type","PERFORMANCE","status","COMPLETED","result_payload","{}")));assertThat(service.passed(deployment)).isFalse();}
}
