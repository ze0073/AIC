package com.shijie.accessibility.hosting;

import com.shijie.accessibility.security.AuthService;
import com.shijie.accessibility.security.ProjectAccessService;
import com.shijie.accessibility.service.IdempotencyService;
import com.shijie.accessibility.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HostingDeletionServiceTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    AuthService auth=mock(AuthService.class);
    ProjectAccessService access=mock(ProjectAccessService.class);
    IdempotencyService idempotency=mock(IdempotencyService.class);
    DockerHostingRuntime runtime=mock(DockerHostingRuntime.class);
    StorageService storage=mock(StorageService.class);
    HostingDeletionService service=new HostingDeletionService(jdbc,auth,access,idempotency,runtime,storage,mock(PlatformTransactionManager.class));

    @BeforeEach void setup() {
        when(auth.current()).thenReturn(new AuthService.UserView("user","user","User",true,LocalDateTime.now()));
        when(idempotency.find(anyString(),anyString())).thenReturn(Optional.empty());
        when(jdbc.queryForList("SELECT * FROM hosted_sites WHERE id=?","site"))
                .thenReturn(List.of(Map.of("id","site","project_id","project")));
        when(jdbc.queryForList("SELECT * FROM hosted_sites WHERE id=? FOR UPDATE","site"))
                .thenReturn(List.of(Map.of("id","site","project_id","project")));
        when(jdbc.queryForObject(contains("role='ADMIN'"),eq(Integer.class),eq("project"),eq("user"))).thenReturn(1);
    }

    @Test void deniesDeletionToNonAdministrators() {
        when(jdbc.queryForObject(contains("role='ADMIN'"),eq(Integer.class),eq("project"),eq("user"))).thenReturn(0);
        assertThatThrownBy(()->service.request("site","key")).hasMessageContaining("只有项目管理员");
        verify(jdbc,never()).update(contains("deletion_status='DELETING'"),any(),any());
        verifyNoInteractions(runtime,storage);
    }

    @Test void busyWorkCannotLoseItsSourceOrRuntime() {
        when(jdbc.queryForObject(contains("FROM test_runs"),eq(Integer.class),eq("project"))).thenReturn(1);
        assertThatThrownBy(()->service.request("site","key")).hasMessageContaining("先取消任务");
        verifyNoInteractions(runtime,storage);
        verify(idempotency,never()).save(any(),any(),any());
    }

    @Test void acceptedDeletionClosesEntrancesAndPersistsBeforeAnyCleanup() {
        assertThat(service.request("site","key")).containsEntry("accepted",true);
        verify(jdbc).update("UPDATE hosted_deployments SET published=FALSE,cancel_requested=TRUE WHERE site_id=?","site");
        verify(idempotency).save("key","hosting-delete:user:site","site");
        verifyNoInteractions(runtime,storage);
    }

    @Test void failedRuntimeCleanupKeepsEveryResourceReferenceForRetry() throws Exception {
        pending();
        doThrow(new IOException("engine unavailable")).when(runtime).deleteWebsite("site",List.of("deployment"),List.of("database"));
        assertThat(service.processNext()).isTrue();
        verify(jdbc).update(contains("deletion_status='DELETE_FAILED'"),any(),any(),eq("site"));
        verify(jdbc,never()).update(startsWith("DELETE FROM"),anyString());
        verifyNoInteractions(storage);
    }

    @Test void resumedDeletionRemovesResourcesBeforeRestrictiveReferencesAndProject() throws Exception {
        pending();
        assertThat(service.processNext()).isTrue();
        var order=inOrder(runtime,storage,jdbc);
        order.verify(runtime).deleteWebsite("site",List.of("deployment"),List.of("database"));
        order.verify(storage).deleteTestRun("run");
        order.verify(storage).deleteProject("project");
        order.verify(jdbc).update("DELETE FROM agent_tasks WHERE project_id=?","project");
        order.verify(jdbc).update("DELETE FROM regression_plans WHERE project_id=?","project");
        order.verify(jdbc).update("DELETE FROM test_runs WHERE project_id=?","project");
        order.verify(jdbc).update("DELETE FROM hosted_deployments WHERE site_id=?","site");
        order.verify(jdbc).update("DELETE FROM hosted_source_jobs WHERE site_id=?","site");
        order.verify(jdbc).update("DELETE FROM hosted_database_resources WHERE site_id=?","site");
        order.verify(jdbc).update("DELETE FROM hosted_sites WHERE id=?","site");
        order.verify(jdbc).update("DELETE FROM projects WHERE id=?","project");
    }

    void pending() {
        when(jdbc.queryForList("SELECT id,project_id FROM hosted_sites WHERE deletion_status='DELETING' ORDER BY updated_at LIMIT 1"))
                .thenReturn(List.of(Map.of("id","site","project_id","project")));
        when(jdbc.queryForList("SELECT id FROM hosted_deployments WHERE site_id=?","site")).thenReturn(List.of(Map.of("id","deployment")));
        when(jdbc.queryForList("SELECT id FROM hosted_database_resources WHERE site_id=?","site")).thenReturn(List.of(Map.of("id","database")));
        when(jdbc.queryForList("SELECT id FROM test_runs WHERE project_id=?","project")).thenReturn(List.of(Map.of("id","run")));
    }
}
