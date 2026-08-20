package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class M52SnapshotCompatibilityTest {
    @TempDir
    Path tempDir;

    @Test
    void legacySnapshotLoadsM52DefaultsWithoutDroppingM51Data() throws Exception {
        Path state = tempDir.resolve("legacy-m52.json");
        Files.writeString(state, """
                {
                  "versions": [],
                  "reviews": [],
                  "installations": [],
                  "audits": [],
                  "authorizations": [],
                  "favorites": [],
                  "configuration": {
                    "teams": [],
                    "roleBindings": [],
                    "categories": [],
                    "tags": [],
                    "collections": [],
                    "platformPolicy": {
                      "pageSizeOptions": [12, 24, 48],
                      "maxPageSize": 48,
                      "minimumClientVersion": "1.0.0",
                      "defaultCollectionVisibility": "public",
                      "policyVersion": 1
                    }
                  }
                }
                """);

        GovernanceStore store = new GovernanceStore(state, List.of());

        assertThat(store.snapshot().invocationEvents()).isEmpty();
        assertThat(store.snapshot().exportJobs()).isEmpty();
        assertThat(store.snapshot().auditIntegrity()).isEmpty();
        assertThat(store.snapshot().retentionPolicy().auditRetentionDays()).isEqualTo(365);
        assertThat(store.snapshot().retentionPolicy().invocationRetentionDays()).isEqualTo(90);
        assertThat(store.snapshot().retentionPolicy().installationRetentionDays()).isEqualTo(90);
        assertThat(store.snapshot().configuration().platformPolicy().maxPageSize()).isEqualTo(48);
    }

    @Test
    void m52MutationsPersistAndPreserveExistingGovernanceConfiguration() throws Exception {
        Path state = tempDir.resolve("m52-persistence.json");
        GovernanceStore store = new GovernanceStore(state, List.of());
        ExportJob job = new ExportJob(UUID.randomUUID(), ExportDataset.INVOCATION_SUMMARY, ExportFormat.CSV,
                ExportFilters.empty(), "alice", "admin", "req-1", ExportJobStatus.QUEUED,
                OffsetDateTime.parse("2026-08-18T10:00:00+08:00"), null, null,
                OffsetDateTime.parse("2026-08-18T10:15:00+08:00"), null, 0, null, List.of(),
                null, null, null, null, null);

        store.addExportJob(job);
        store.updateRetentionPolicy(new RetentionPolicy(2, 365, 120, 100, "admin",
                OffsetDateTime.parse("2026-08-18T10:01:00+08:00")));

        GovernanceStore restarted = new GovernanceStore(state, List.of());

        assertThat(restarted.snapshot().exportJobs()).extracting(ExportJob::jobId).containsExactly(job.jobId());
        assertThat(restarted.snapshot().retentionPolicy().policyVersion()).isEqualTo(2);
        assertThat(restarted.snapshot().configuration().platformPolicy().pageSizeOptions())
                .containsExactly(12, 24, 48);
    }
}
