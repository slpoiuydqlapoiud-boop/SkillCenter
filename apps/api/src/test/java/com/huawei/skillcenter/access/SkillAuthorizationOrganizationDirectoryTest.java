package com.huawei.skillcenter.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceConfiguration;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.OrganizationDirectoryClient;
import com.huawei.skillcenter.governance.OrganizationDirectoryProperties;
import com.huawei.skillcenter.governance.OrganizationDirectorySnapshot;
import com.huawei.skillcenter.governance.OrganizationDirectoryStore;
import com.huawei.skillcenter.governance.OrganizationDirectorySyncService;
import com.huawei.skillcenter.governance.RoleBinding;
import com.huawei.skillcenter.governance.ReviewTask;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.governance.TeamDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillAuthorizationOrganizationDirectoryTest {
    private static final Instant NOW = Instant.parse("2026-08-25T06:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void httpDirectoryRequiresDirectoryMembershipAndJwtTeamClaimForVisibility() {
        GovernanceStore governanceStore = new GovernanceStore(tempDir.resolve("governance.json"), List.of());
        governanceStore.updateGovernanceConfiguration(new GovernanceConfiguration(
                List.of(team("team-a", List.of("local-member"), "active")),
                List.of(binding("external-user", "maintainer", "team-a", "active")),
                List.of(), List.of(), List.of(), null), null);
        SkillScopeStore scopeStore = new SkillScopeStore(tempDir.resolve("scopes.json"),
                new ObjectMapper().findAndRegisterModules());
        governanceStore.createPendingVersion(version(), review(), audit());
        scopeStore.create(new SkillScope("skill-team", SkillVisibility.TEAM, "team-a", List.of(), 1,
                "admin", NOW, "admin", NOW));

        OrganizationDirectorySyncService directory = directory(governanceStore,
                snapshot(List.of("external-user")), NOW);
        directory.sync(new Actor("admin", "admin"), "req-directory");
        SkillAuthorizationService service = new SkillAuthorizationService(scopeStore, governanceStore,
                Clock.fixed(NOW, ZoneOffset.UTC), directory);
        Actor claimed = new Actor("external-user", "viewer", Set.of("team-a"), true);
        assertEquals("ACTIVE", directory.status().status());
        assertTrue(directory.allows(claimed, "team-a"));
        assertEquals(SkillVisibility.TEAM, service.effectiveScope("skill-team").visibility());

        assertDoesNotThrow(() -> service.requireVisible("skill-team", claimed, SkillVisibilityContext.CATALOG));
        assertThatThrownBy(() -> service.requireVisible("skill-team",
                new Actor("external-user", "viewer", Set.of("other-team"), true), SkillVisibilityContext.CATALOG))
                .isInstanceOf(SkillNotVisibleException.class);
        assertThatThrownBy(() -> service.requireVisible("skill-team",
                new Actor("local-member", "viewer"), SkillVisibilityContext.CATALOG))
                .isInstanceOf(SkillNotVisibleException.class);
    }

    @Test
    void directoryFailureDeniesVisibilityButMaintainerBindingStillControlsManagement() {
        GovernanceStore governanceStore = new GovernanceStore(tempDir.resolve("governance-failure.json"), List.of());
        governanceStore.updateGovernanceConfiguration(new GovernanceConfiguration(
                List.of(team("team-a", List.of(), "active")),
                List.of(binding("external-user", "maintainer", "team-a", "active")),
                List.of(), List.of(), List.of(), null), null);
        SkillScopeStore scopeStore = new SkillScopeStore(tempDir.resolve("scopes-failure.json"),
                new ObjectMapper().findAndRegisterModules());
        scopeStore.create(new SkillScope("skill-team", SkillVisibility.TEAM, "team-a", List.of(), 1,
                "admin", NOW, "admin", NOW));
        OrganizationDirectoryProperties properties = httpProperties();
        OrganizationDirectorySyncService directory = new OrganizationDirectorySyncService(properties,
                new OrganizationDirectoryStore(tempDir.resolve("directory-failure.json"),
                        new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC)),
                () -> { throw new com.huawei.skillcenter.governance.OrganizationDirectoryUnavailableException("DIRECTORY_TIMEOUT"); },
                governanceStore, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> directory.sync(new Actor("admin", "admin"), "req-failure"))
                .isInstanceOf(com.huawei.skillcenter.governance.OrganizationDirectoryUnavailableException.class);

        SkillAuthorizationService service = new SkillAuthorizationService(scopeStore, governanceStore,
                Clock.fixed(NOW, ZoneOffset.UTC), directory);

        assertThatThrownBy(() -> service.requireVisible("skill-team",
                new Actor("external-user", "developer", Set.of("team-a"), true), SkillVisibilityContext.CATALOG))
                .isInstanceOf(SkillNotVisibleException.class);
        assertThatThrownBy(() -> service.requireManage("skill-team",
                new Actor("external-user", "developer", Set.of("team-a"), true)))
                .isInstanceOf(SkillManageForbiddenException.class);
    }

    private OrganizationDirectorySyncService directory(GovernanceStore governanceStore,
                                                        OrganizationDirectorySnapshot snapshot, Instant now) {
        return new OrganizationDirectorySyncService(httpProperties(),
                new OrganizationDirectoryStore(tempDir.resolve("directory-" + System.nanoTime() + ".json"),
                        new ObjectMapper().findAndRegisterModules(), Clock.fixed(now, ZoneOffset.UTC)),
                () -> snapshot, governanceStore, Clock.fixed(now, ZoneOffset.UTC));
    }

    private OrganizationDirectoryProperties httpProperties() {
        OrganizationDirectoryProperties properties = new OrganizationDirectoryProperties();
        properties.setMode("http");
        properties.setEndpoint("https://directory.example.test/snapshot");
        properties.setCredentialRef("secret://env/DIRECTORY_TOKEN");
        properties.validate();
        return properties;
    }

    private OrganizationDirectorySnapshot snapshot(List<String> members) {
        return new OrganizationDirectorySnapshot("organization-directory.v1", "corp-directory", "rev-1", NOW,
                List.of(new OrganizationDirectorySnapshot.Team("team-a", "A", "active", members)));
    }

    private TeamDefinition team(String id, List<String> members, String status) {
        return new TeamDefinition(id, id, "", members.isEmpty() ? "" : members.get(0), members, status,
                NOW.minusSeconds(10), NOW.minusSeconds(10));
    }

    private RoleBinding binding(String userId, String role, String teamId, String status) {
        return new RoleBinding(userId, role, teamId, status, "admin", NOW.minusSeconds(10));
    }

    private SkillVersion version() {
        return new SkillVersion("pkg-team", "skill-team", "1.0.0", "published", "a".repeat(64), 1L, "",
                "owner", NOW.minusSeconds(10), "reviewer", NOW.minusSeconds(10), "review-1", null, null, null, null, "low");
    }

    private ReviewTask review() {
        return new ReviewTask("review-1", "pkg-team", "skill-team", "1.0.0", "approved", "owner",
                NOW.minusSeconds(10), "reviewer", NOW.minusSeconds(10), null);
    }

    private AuditEvent audit() {
        return new AuditEvent("audit-1", "PACKAGE_IMPORTED", "SKILL_VERSION", "pkg-team", "system",
                "admin", "seed", NOW.minusSeconds(10), Map.of("skillId", "skill-team"));
    }

}
