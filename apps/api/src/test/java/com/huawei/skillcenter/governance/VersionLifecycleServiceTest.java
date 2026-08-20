package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.events.InvocationEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionLifecycleServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void allowsPublishedToDeprecatedAndPersistsReason() {
        GovernanceStore store = storeWithVersions(version("p1", "demo", "1.0.0", "published"),
                version("p2", "demo", "1.1.0", "published"));
        VersionLifecycleService service = new VersionLifecycleService(store, new InvocationEventService());

        SkillVersion updated = service.deprecate("demo", "1.0.0",
                new VersionLifecycleRequest("security fix", "1.1.0"), new Actor("admin-1", "admin"), "req-1");

        assertThat(updated.status()).isEqualTo("deprecated");
        assertThat(updated.statusReason()).isEqualTo("security fix");
        assertThat(updated.replacementVersion()).isEqualTo("1.1.0");
        assertThat(store.snapshot().audits()).extracting(AuditEvent::action)
                .contains("VERSION_DEPRECATED");
    }

    @Test
    void rejectsInvalidTransitionsAndNonAdminWrites() {
        GovernanceStore store = storeWithVersions(version("p1", "demo", "1.0.0", "withdrawn"));
        VersionLifecycleService service = new VersionLifecycleService(store, new InvocationEventService());

        assertThatThrownBy(() -> service.withdraw("demo", "1.0.0",
                new VersionLifecycleRequest("again", null), new Actor("admin-1", "admin"), "req-1"))
                .isInstanceOf(VersionStateConflictException.class);
        assertThatThrownBy(() -> service.deprecate("demo", "1.0.0",
                new VersionLifecycleRequest("reason", null), new Actor("reviewer-1", "reviewer"), "req-2"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.deprecate("demo", "1.0.0",
                new VersionLifecycleRequest("", null), new Actor("admin-1", "admin"), "req-3"))
                .isInstanceOf(InvalidLifecycleRequestException.class);
    }

    @Test
    void impactAggregatesInstallationsAndCurrentProcessInvocations() {
        GovernanceStore store = storeWithVersions(version("p1", "demo", "1.0.0", "published"));
        store.addInstallation(new InstallationRecord("i1", "m1", "demo", "1.0.0", "codex", "1", "alice",
                "installed", Instant.now(), Instant.now(), "team-a", null, "cli", null, null, null, null),
                new AuditEvent("i-audit-1", "INSTALLATION_REQUESTED", "INSTALLATION", "i1", "alice", "viewer", "req", Instant.now(), Map.of()));
        store.addInstallation(new InstallationRecord("i2", "m2", "demo", "1.0.0", "ide", "1", "alice",
                "failed", Instant.now(), Instant.now(), "team-a", null, "cli", null, "ERR", null, null),
                new AuditEvent("i-audit-2", "INSTALLATION_REQUESTED", "INSTALLATION", "i2", "alice", "viewer", "req", Instant.now(), Map.of()));
        InvocationEventService events = new InvocationEventService();
        events.ingest(new com.huawei.skillcenter.events.InvocationEvent("1.0", java.util.UUID.randomUUID(),
                java.time.OffsetDateTime.now(), "demo", "1.0.0",
                new com.huawei.skillcenter.events.InvocationEvent.Subject("alice", "team-a"),
                new com.huawei.skillcenter.events.InvocationEvent.Client("codex", "1"), "session-1234567890",
                "success", 10, null, new com.huawei.skillcenter.events.InvocationEvent.Usage("model", 1, 1)));
        VersionImpact impact = new VersionLifecycleService(store, events)
                .impact("demo", "1.0.0", new Actor("reviewer-1", "reviewer"));

        assertThat(impact.installationCount()).isEqualTo(2);
        assertThat(impact.activeInstallationCount()).isEqualTo(1);
        assertThat(impact.userCount()).isEqualTo(1);
        assertThat(impact.teamCount()).isEqualTo(1);
        assertThat(impact.clientTypes()).containsEntry("codex", 1L).containsEntry("ide", 1L);
        assertThat(impact.invocationCount()).isEqualTo(1);
        assertThat(impact.activeInvocationUsers()).isEqualTo(1);
    }

    private GovernanceStore storeWithVersions(SkillVersion... versions) {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-" + System.nanoTime() + ".json"), List.of());
        for (SkillVersion version : versions) {
            store.createPendingVersion(version, new ReviewTask("r-" + version.packageId(), version.packageId(),
                    version.skillId(), version.version(), "approved", version.uploadedBy(), version.uploadedAt(),
                    version.publishedBy(), version.publishedAt(), null), new AuditEvent("a-" + version.packageId(),
                    "PACKAGE_APPROVED", "SKILL_VERSION", version.packageId(), "admin", "admin", "seed",
                    Instant.now(), Map.of()));
        }
        return store;
    }

    private SkillVersion version(String packageId, String skillId, String version, String status) {
        return new SkillVersion(packageId, skillId, version, status, "a".repeat(64), 1, "", "alice",
                Instant.parse("2026-08-17T00:00:00Z"), "reviewer", Instant.parse("2026-08-17T00:01:00Z"),
                "r-1");
    }
}
