package com.shijie.accessibility.hosting;

import com.shijie.accessibility.online.CredentialCipher;
import com.shijie.accessibility.security.AuthService;
import com.shijie.accessibility.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;

class HostingWorkerRecoveryTest {
    @Test void restoresTheCurrentDockerPortAfterAnEngineRestart() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        DockerHostingRuntime runtime=mock(DockerHostingRuntime.class);
        when(jdbc.queryForList("SELECT id FROM hosted_deployments WHERE status='RUNNING'"))
                .thenReturn(List.of(Map.of("id","deployment")));
        when(runtime.alive("deployment")).thenReturn(true);
        when(runtime.accessiblePort("deployment")).thenReturn(62629);
        worker(jdbc,runtime).recover();
        verify(jdbc).update("UPDATE hosted_deployments SET upstream_port=? WHERE id=?",62629,"deployment");
    }

    @Test void doesNotKeepAStoppedContainerMarkedAsRunning() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        DockerHostingRuntime runtime=mock(DockerHostingRuntime.class);
        when(jdbc.queryForList("SELECT id FROM hosted_deployments WHERE status='RUNNING'"))
                .thenReturn(List.of(Map.of("id","deployment")));
        worker(jdbc,runtime).recover();
        verify(jdbc).update(eq("UPDATE hosted_deployments SET status='STOPPED',published=FALSE,error_message='运行容器已经停止，请重新部署',updated_at=? WHERE id=?"),any(),eq("deployment"));
    }

    private HostingWorker worker(JdbcTemplate jdbc,DockerHostingRuntime runtime) {
        return new HostingWorker(mock(HostingService.class),runtime,mock(HostingCommands.class),jdbc,
                mock(StorageService.class),mock(CredentialCipher.class),mock(AuthService.class),
                mock(TestRunService.class),mock(IssueRepairService.class),mock(HostingTargetRegistry.class),
                mock(PlatformTransactionManager.class));
    }
}
