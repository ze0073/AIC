package com.shijie.accessibility.service;

import com.shijie.accessibility.mapper.ArtifactMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReportSnapshotServiceTest {
    @Test void failedPdfCanRetryTheSameFrozenReportWithoutChangingItsContent() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);ReportService reports=mock(ReportService.class);
        var service=spy(new ReportSnapshotService(jdbc,reports,new ObjectMapper(),mock(PlatformTransactionManager.class),mock(ArtifactMapper.class),mock(StorageService.class)));
        var report=mock(ReportService.Snapshot.class);
        var frozen=new ReportSnapshotService.Frozen("snapshot","hash",LocalDateTime.of(2026,9,19,12,0),report);
        doReturn(frozen).when(service).get("snapshot");
        when(jdbc.queryForObject(anyString(),eq(String.class),eq("snapshot"))).thenReturn(null);
        var file=new ReportService.ReportFile(Path.of("report.pdf"),"application/pdf","report.pdf","artifact");
        when(reports.generate(report,"snapshot","hash","pdf")).thenThrow(new IllegalStateException("browser failed")).thenReturn(file);
        assertThatThrownBy(()->service.export("snapshot","pdf")).hasMessage("browser failed");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        assertThat(service.export("snapshot","pdf")).isSameAs(file);
        verify(jdbc).update("UPDATE report_snapshots SET pdf_artifact_id=? WHERE id=?","artifact","snapshot");
        verify(reports,times(2)).generate(report,"snapshot","hash","pdf");
    }
    @Test void invalidKeysAreRejectedBeforeOpeningATransaction() {
        var transactions=mock(PlatformTransactionManager.class);
        var service=new ReportSnapshotService(mock(JdbcTemplate.class),mock(ReportService.class),new ObjectMapper(),transactions,mock(ArtifactMapper.class),mock(StorageService.class));
        for(String key:new String[]{null,""," ","x".repeat(121)}) assertThatThrownBy(()->service.create("scan",key)).hasMessageContaining("Idempotency-Key");
        verifyNoInteractions(transactions);
    }
}
