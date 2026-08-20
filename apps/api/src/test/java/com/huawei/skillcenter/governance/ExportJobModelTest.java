package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportJobModelTest {
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-08-18T10:00:00+08:00");

    @Test
    void rejectsNegativeRowCount() {
        assertThatThrownBy(() -> queuedJob(-1, CREATED_AT, CREATED_AT.plusMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rowCount");
    }

    @Test
    void rejectsExpiryBeforeCreation() {
        assertThatThrownBy(() -> queuedJob(0, CREATED_AT, CREATED_AT.minusMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresAt");
    }

    @Test
    void rejectsRetentionPolicyBelowSafetyFloor() {
        assertThatThrownBy(() -> new RetentionPolicy(1, 364, 90, 90, "admin",
                CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auditRetentionDays");

        assertThatThrownBy(() -> new RetentionPolicy(1, 365, 29, 90, "admin",
                CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invocationRetentionDays");
    }

    private ExportJob queuedJob(long rowCount, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        return new ExportJob(UUID.randomUUID(), ExportDataset.AUDIT_SUMMARY, ExportFormat.JSON,
                ExportFilters.empty(), "alice", "admin", "req-1", ExportJobStatus.QUEUED,
                createdAt, null, null, expiresAt, null, rowCount, null, List.of(),
                null, null, null, null, null);
    }
}
