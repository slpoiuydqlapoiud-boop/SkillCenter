package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.events.GovernanceInvocationEventStore;
import com.huawei.skillcenter.events.InvocationEvent;
import com.huawei.skillcenter.events.InvocationEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void previewsAndExecutesInvocationAndInstallationCleanupWhileKeepingAudits() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        events.putIfAbsent(event(Instant.now().minusSeconds(91L * 86_400L)));
        events.putIfAbsent(event(Instant.now().minusSeconds(2L * 86_400L)));
        store.addInstallation(installation(Instant.now().minusSeconds(91L * 86_400L)), null);
        store.addInstallation(installation(Instant.now().minusSeconds(2L * 86_400L)), null);
        store.addAudit(new AuditEvent("audit-1", "RETENTION_PREVIEW", "RETENTION", "policy", "admin", "admin",
                "req-1", Instant.now().minusSeconds(400L * 86_400L), java.util.Map.of()));

        RetentionService service = new RetentionService(store, events);
        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "req-preview");

        assertThat(preview.invocationEligibleCount()).isEqualTo(1);
        assertThat(preview.installationEligibleCount()).isEqualTo(1);
        assertThat(preview.auditArchiveEligibleCount()).isEqualTo(1);

        RetentionExecutionResult result = service.execute(new RetentionExecutionRequest(
                preview.previewId(), preview.policyVersion(), "batch-1"), new Actor("admin", "admin"), "req-execute");

        assertThat(result.invocationDeleted()).isEqualTo(1);
        assertThat(result.installationDeleted()).isEqualTo(1);
        assertThat(store.snapshot().invocationEvents()).hasSize(1);
        assertThat(store.snapshot().installations()).hasSize(1);
        assertThat(store.snapshot().audits()).extracting(AuditEvent::auditId).contains("audit-1");
    }

    @Test
    void reviewerCannotUpdateOrExecuteRetentionPolicy() {
        RetentionService service = new RetentionService(
                new GovernanceStore(tempDir.resolve("state.json"), List.of()),
                new GovernanceInvocationEventStore(new GovernanceStore(tempDir.resolve("state-2.json"), List.of())));

        assertThatThrownBy(() -> service.update(new RetentionPolicyMutation(1, 365, 90, 90),
                new Actor("reviewer", "reviewer"), "req-update"))
                .isInstanceOf(RetentionException.class)
                .hasMessageContaining("admin");
    }

    private InvocationEvent event(Instant occurredAt) {
        return new InvocationEvent("1.0", UUID.randomUUID(), occurredAt.atOffset(ZoneOffset.UTC), "eox-query", "1.2.0",
                new InvocationEvent.Subject("alice", "team-a"), new InvocationEvent.Client("codex", "1.0.0"),
                "session_1234567890", "success", 42, null, null);
    }

    private InstallationRecord installation(Instant requestedAt) {
        return new InstallationRecord("i-" + UUID.randomUUID(), "m1", "eox-query", "1.2.0", "codex", "1.0.0",
                "alice", "installed", requestedAt, requestedAt.plusSeconds(60));
    }
}
