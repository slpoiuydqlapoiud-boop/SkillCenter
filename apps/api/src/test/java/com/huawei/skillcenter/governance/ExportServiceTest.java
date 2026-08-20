package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.events.GovernanceInvocationEventStore;
import com.huawei.skillcenter.events.InvocationEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsCompletesAndDownloadsRedactedAuditExport() throws Exception {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        store.addAudit(new AuditEvent("a1", "EXPORT_CREATED", "EXPORT", "job-1", "alice", "admin", "req-1",
                Instant.parse("2026-08-18T02:00:00Z"),
                Map.of("dataset", "AUDIT_SUMMARY", "prompt", "must-not-export")));
        InvocationEventStore eventStore = new GovernanceInvocationEventStore(store);
        ExportService service = new ExportService(store, new AuditProjectionService(), eventStore,
                tempDir.resolve("exports"));

        ExportJob created = service.create(new ExportRequest(ExportDataset.AUDIT_SUMMARY, ExportFormat.CSV,
                ExportFilters.empty()), new Actor("alice", "admin"), "req-create");
        ExportJob completed = service.awaitCompletion(created.jobId());

        assertThat(completed.status()).isEqualTo(ExportJobStatus.COMPLETED);
        assertThat(completed.rowCount()).isGreaterThanOrEqualTo(2);
        assertThat(completed.artifactPath()).doesNotContain(tempDir.toString());
        Path artifact = tempDir.resolve("exports").resolve(completed.artifactPath());
        assertThat(Files.readString(artifact)).contains("EXPORT_CREATED").doesNotContain("must-not-export");

        DownloadUrlResponse url = service.issueDownloadUrl(created.jobId(), new Actor("alice", "admin"), "req-url");
        ExportDownload download = service.download(created.jobId(), url.token(), new Actor("alice", "admin"), "req-download");
        assertThat(new String(download.content(), StandardCharsets.UTF_8)).contains("EXPORT_CREATED");
        assertThatThrownBy(() -> service.download(created.jobId(), url.token(), new Actor("alice", "admin"), "req-repeat"))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rejectsUnsupportedRoleAndExpiredToken() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        ExportService service = new ExportService(store, new AuditProjectionService(),
                new GovernanceInvocationEventStore(store), tempDir.resolve("exports"));

        assertThatThrownBy(() -> service.create(new ExportRequest(ExportDataset.AUDIT_SUMMARY, ExportFormat.JSON,
                ExportFilters.empty()), new Actor("bob", "viewer"), "req-viewer"))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("permission");
    }
}
