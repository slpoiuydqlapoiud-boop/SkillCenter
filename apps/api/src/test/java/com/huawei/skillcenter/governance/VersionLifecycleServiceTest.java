package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.distribution.DistributionAuthorization;
import com.huawei.skillcenter.events.InvocationEventService;
import com.huawei.skillcenter.notification.NotificationRecord;
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
    void lifecycleTransitionPreservesSecurityEvidence() {
        SkillVersion original = new SkillVersion(
                "p1", "demo", "1.0.0", "published", "a".repeat(64), 1, "", "alice",
                Instant.parse("2026-08-17T00:00:00Z"), "reviewer", Instant.parse("2026-08-17T00:01:00Z"),
                "r-1", null, "1.1.0", null, null, "low",
                new SecurityScanEvidence("PASSED", "local-package-security", "1", List.of()));
        GovernanceStore store = storeWithVersions(original,
                version("p2", "demo", "1.1.0", "published"));

        SkillVersion updated = new VersionLifecycleService(store, new InvocationEventService()).deprecate(
                "demo", "1.0.0", new VersionLifecycleRequest("security fix", "1.1.0"),
                new Actor("admin-1", "admin"), "req-security-preserve");

        assertThat(updated.securityEvidence()).isEqualTo(original.securityEvidence());
        assertThat(store.snapshot().versions().get(0).securityEvidence().scannerId())
                .isEqualTo("local-package-security");
    }

    @Test
    void withdrawingVersionRevokesPendingAuthorizationsAndNotifiesImpactedUsers() {
        GovernanceStore store = storeWithVersions(version("p1", "demo", "1.0.0", "published"),
                version("p2", "demo", "1.1.0", "published"));
        addInstallation(store, "i-alice", "alice", "installed");
        store.addAuthorization(authorization("token-a", "alice", null, null));
        store.addAuthorization(authorization("token-b", "bob", null, null));
        store.addAuthorization(authorization("token-consumed", "carol", Instant.parse("2026-08-26T00:00:00Z"), null));

        new VersionLifecycleService(store, new InvocationEventService()).withdraw("demo", "1.0.0",
                new VersionLifecycleRequest("critical vulnerability", "1.1.0"),
                new Actor("admin-1", "admin"), "req-withdraw");

        DistributionAuthorization pendingAlice = store.snapshot().authorizations().stream()
                .filter(item -> item.tokenId().equals("token-a")).findFirst().orElseThrow();
        DistributionAuthorization pendingBob = store.snapshot().authorizations().stream()
                .filter(item -> item.tokenId().equals("token-b")).findFirst().orElseThrow();
        DistributionAuthorization consumed = store.snapshot().authorizations().stream()
                .filter(item -> item.tokenId().equals("token-consumed")).findFirst().orElseThrow();
        assertThat(pendingAlice.revokedAt()).isNotNull();
        assertThat(pendingAlice.revokeReason()).isEqualTo("VERSION_WITHDRAWN");
        assertThat(pendingBob.revokedAt()).isNotNull();
        assertThat(consumed.revokedAt()).isNull();
        assertThatThrownBy(() -> store.consumeAuthorization("digest-token-a",
                Instant.parse("2026-08-26T12:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("authorization has been revoked");

        assertThat(store.snapshot().notifications()).extracting(NotificationRecord::userId)
                .containsExactlyInAnyOrder("alice", "bob");
        assertThat(store.snapshot().notifications()).allSatisfy(notification -> {
            assertThat(notification.type()).isEqualTo("lifecycle");
            assertThat(notification.detail()).contains("demo", "1.0.0", "critical vulnerability");
            assertThat(notification.detail()).doesNotContain("token-a", "token-b");
        });
        AuditEvent revokeAudit = store.snapshot().audits().stream()
                .filter(item -> item.action().equals("DISTRIBUTION_AUTHORIZATIONS_REVOKED"))
                .findFirst().orElseThrow();
        assertThat(revokeAudit.metadata()).containsEntry("revokedCount", "2");
        assertThat(revokeAudit.metadata()).doesNotContainKey("tokenDigest");
    }

    @Test
    void deprecatingVersionNotifiesImpactedUsersWithoutRevokingPendingAuthorization() {
        GovernanceStore store = storeWithVersions(version("p1", "demo", "1.0.0", "published"),
                version("p2", "demo", "1.1.0", "published"));
        addInstallation(store, "i-alice", "alice", "installed");
        store.addAuthorization(authorization("token-a", "alice", null, null));

        new VersionLifecycleService(store, new InvocationEventService()).deprecate("demo", "1.0.0",
                new VersionLifecycleRequest("superseded", "1.1.0"),
                new Actor("admin-1", "admin"), "req-deprecate");

        assertThat(store.snapshot().authorizations().get(0).revokedAt()).isNull();
        assertThat(store.snapshot().notifications()).extracting(NotificationRecord::userId)
                .containsExactly("alice");
        assertThat(store.snapshot().notifications().get(0).title()).isEqualTo("Skill 版本已废弃");
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

    private void addInstallation(GovernanceStore store, String installationId, String userId, String status) {
        store.addInstallation(new InstallationRecord(installationId, "m-" + installationId, "demo", "1.0.0",
                        "codex", "1", userId, status, Instant.now(), Instant.now(), "team-a", null,
                        "cli", null, null, null, null),
                new AuditEvent("audit-" + installationId, "INSTALLATION_REQUESTED", "INSTALLATION",
                        installationId, userId, "viewer", "seed-installation", Instant.now(), Map.of()));
    }

    private DistributionAuthorization authorization(String tokenId, String requestedBy, Instant consumedAt,
                                                    Instant revokedAt) {
        return new DistributionAuthorization(tokenId, "digest-" + tokenId, "demo", "1.0.0",
                "installation-" + requestedBy, requestedBy, "codex", "1", "cli",
                Instant.parse("2026-08-26T00:00:00Z"), Instant.parse("2026-08-27T00:00:00Z"),
                consumedAt, revokedAt, revokedAt == null ? null : "VERSION_WITHDRAWN");
    }
}
