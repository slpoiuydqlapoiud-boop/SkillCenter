package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.events.InvocationEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditProjectionServiceTest {
    private final AuditProjectionService service = new AuditProjectionService();

    @Test
    void auditProjectionOnlyKeepsWhitelistedMetadata() {
        AuditEvent event = new AuditEvent("a1", "EXPORT_CREATED", "EXPORT", "job-1", "alice", "admin",
                "req-1", Instant.parse("2026-08-18T02:00:00Z"),
                Map.of("dataset", "AUDIT_SUMMARY", "prompt", "secret", "token", "secret"));

        AuditSummaryRow row = service.auditSummary(List.of(event)).get(0);

        assertThat(row.auditId()).isEqualTo("a1");
        assertThat(row.metadata()).containsEntry("dataset", "AUDIT_SUMMARY");
        assertThat(row.metadata()).doesNotContainKeys("prompt", "token");
        assertThat(row.toString()).doesNotContain("secret");
    }

    @Test
    void invocationProjectionAggregatesWithoutSensitiveUsageFields() {
        InvocationEvent success = event("2026-08-18T10:00:00+08:00", "success", null, 100);
        InvocationEvent failure = event("2026-08-18T10:30:00+08:00", "failure", "TIMEOUT", 300);

        List<InvocationSummaryRow> rows = service.invocationSummary(List.of(success, failure), ExportFilters.empty());
        InvocationSummaryRow row = rows.stream().filter(item -> "failure".equals(item.status())).findFirst().orElseThrow();

        assertThat(row.skillId()).isEqualTo("eox-query");
        assertThat(rows).hasSize(2);
        assertThat(row.count()).isEqualTo(1);
        assertThat(row.avgDurationMs()).isEqualTo(300);
        assertThat(row.p95DurationMs()).isEqualTo(300);
        assertThat(row.errorCount()).isEqualTo(1);
        assertThat(row.toString()).doesNotContain("gpt-5", "session_", "token");
    }

    @Test
    void installationProjectionDoesNotExposeDeviceOrPath() {
        InstallationSummaryRow row = service.installationSummary(List.of(new InstallationRecord(
                "i1", "m1", "eox-query", "1.2.0", "codex", "1.0.0", "alice", "installed",
                Instant.parse("2026-08-18T02:00:00Z"), Instant.parse("2026-08-18T02:01:00Z"),
                "team-a", "device-secret", "download", "event-1", null, Instant.parse("2026-08-18T02:01:00Z"), null)),
                ExportFilters.empty()).get(0);

        assertThat(row.count()).isEqualTo(1);
        assertThat(row.toString()).doesNotContain("device-secret", "download", "event-1");
    }

    private InvocationEvent event(String occurredAt, String status, String errorCode, long duration) {
        return new InvocationEvent("1.0", UUID.randomUUID(), OffsetDateTime.parse(occurredAt), "eox-query", "1.2.0",
                new InvocationEvent.Subject("alice", "team-a"), new InvocationEvent.Client("codex", "1.0.0"),
                "session_1234567890", status, duration, errorCode,
                new InvocationEvent.Usage("gpt-5", 100, 200));
    }
}
