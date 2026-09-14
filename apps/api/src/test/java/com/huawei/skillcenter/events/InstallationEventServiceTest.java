package com.huawei.skillcenter.events;

import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InstallationEventServiceTest {
    @TempDir
    Path tempDir;

    private GovernanceStore store;
    private InstallationEventService service;

    @BeforeEach
    void setUp() {
        store = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        store.addInstallation(requestedInstallation(), new AuditEvent("audit-1", "INSTALL_REQUESTED", "INSTALLATION",
                "installation-1", "alice", "viewer", "request-1", Instant.parse("2026-08-17T08:00:00Z"), Map.of()));
        service = new InstallationEventService(store);
    }

    @Test
    void successfulInstallEventMovesRequestedInstallationToInstalled() {
        InstallationEventService.EventResult result = service.ingest(validInstallEvent());

        assertThat(result.accepted()).isTrue();
        assertThat(store.findInstallation("installation-1").orElseThrow().status()).isEqualTo("installed");
        assertThat(store.findInstallation("installation-1").orElseThrow().installedAt()).isNotNull();
    }

    @Test
    void duplicateInstallationEventDoesNotApplyTheTransitionTwice() {
        InstallationEvent event = validInstallEvent();

        assertThat(service.ingest(event).duplicate()).isFalse();
        assertThat(service.ingest(event).duplicate()).isTrue();
        assertThat(store.findInstallation("installation-1").orElseThrow().lastEventId()).isEqualTo(event.eventId().toString());
    }

    @Test
    void duplicateInstallationEventRemainsIdempotentAfterServiceRestart() {
        InstallationEvent event = validInstallEvent();
        assertThat(service.ingest(event).duplicate()).isFalse();

        GovernanceStore restartedStore = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        InstallationEventService restarted = new InstallationEventService(restartedStore);

        assertThat(restarted.ingest(event)).isEqualTo(new InstallationEventService.EventResult(
                event.eventId(), true, true, null));
        assertThat(restartedStore.snapshot().audits()).filteredOn(audit ->
                "INSTALLATION_EVENT_ACCEPTED".equals(audit.action())).hasSize(1);
    }

    @Test
    void conflictingInstallationEventRemainsRejectedAfterServiceRestart() {
        InstallationEvent event = validInstallEvent();
        assertThat(service.ingest(event).duplicate()).isFalse();

        InstallationEvent conflicting = new InstallationEvent(event.schemaVersion(), event.eventId(),
                event.occurredAt(), event.skillId(), event.version(), event.subject(), event.client(),
                event.deviceId(), event.action(), event.method(), "failure", "INSTALLATION_FAILED");
        GovernanceStore restartedStore = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        InstallationEventService restarted = new InstallationEventService(restartedStore);

        assertThat(restarted.ingest(conflicting)).isEqualTo(new InstallationEventService.EventResult(
                event.eventId(), false, false, "EVENT_ID_CONFLICT"));
        assertThat(restartedStore.findInstallation("installation-1").orElseThrow().status())
                .isEqualTo("installed");
    }

    private InstallationRecord requestedInstallation() {
        return new InstallationRecord("installation-1", "manifest-1", "eox-query", "1.2.0",
                "codex", "1.0.0", "alice", "requested", Instant.parse("2026-08-17T08:00:00Z"),
                Instant.parse("2026-08-17T08:00:00Z"));
    }

    private InstallationEvent validInstallEvent() {
        return new InstallationEvent("1.0", UUID.fromString("2d8f1d8f-68a2-4c50-a4d5-ec5f6d1f9fd2"),
                OffsetDateTime.parse("2026-08-17T08:01:00+08:00"), "eox-query", "1.2.0",
                new InstallationEvent.Subject("alice", "network-team"),
                new InstallationEvent.Client("codex", "1.0.0"), "device_123456789012", "install",
                "one-click", "success", null);
    }
}
