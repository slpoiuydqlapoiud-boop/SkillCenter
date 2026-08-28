package com.huawei.skillcenter.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceConfiguration;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleBinding;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T03:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void publicScopeIsVisibleButManageAndSubmitUseLatestNonWithdrawnUploaderFallback() {
        TestFixture fixture = fixture();
        fixture.addVersion(version("pkg-public-1", "skill-public", "1.0.0", "published", "alice",
                NOW.minusSeconds(120)));
        fixture.addVersion(version("pkg-public-2", "skill-public", "1.1.0", "deprecated", "carol",
                NOW.minusSeconds(60)));

        SkillAuthorizationService service = fixture.service();

        service.requireVisible("skill-public", actor("outsider", "viewer"), SkillVisibilityContext.CATALOG);
        service.requireManage("skill-public", actor("carol", "developer"));
        service.requireSubmitVersion("skill-public", actor("carol", "maintainer"));

        assertThatThrownBy(() -> service.requireManage("skill-public", actor("outsider", "viewer")))
                .isInstanceOf(SkillManageForbiddenException.class);
        assertThat(service.effectiveScope("skill-public").visibility()).isEqualTo(SkillVisibility.PUBLIC);
    }

    @Test
    void teamScopeRequiresActiveTeamMembershipAndActiveMaintainerBindingToManage() {
        TestFixture fixture = fixture();
        fixture.addVersion(version("pkg-team", "skill-team", "1.0.0", "published", "owner",
                NOW.minusSeconds(30)));
        fixture.configure(config(
                List.of(team("team-a", List.of("member", "maintainer-member"), "active")),
                List.of(
                        binding("member", "viewer", "team-a", "active"),
                        binding("maintainer-member", "maintainer", "team-a", "active"),
                        binding("inactive-binding", "maintainer", "team-a", "inactive"),
                        binding("stranger", "maintainer", "team-b", "active")
                )));
        fixture.scopeStore().create(scope("skill-team", SkillVisibility.TEAM, "team-a", List.of("explicit-maint"), 1));

        SkillAuthorizationService service = fixture.service();

        service.requireVisible("skill-team", actor("member", "viewer"), SkillVisibilityContext.CATALOG);
        service.requireManage("skill-team", actor("maintainer-member", "maintainer"));

        assertThatThrownBy(() -> service.requireVisible("skill-team", actor("inactive-binding", "maintainer"),
                SkillVisibilityContext.CATALOG)).isInstanceOf(SkillNotVisibleException.class);
        assertThatThrownBy(() -> service.requireManage("skill-team", actor("member", "viewer")))
                .isInstanceOf(SkillManageForbiddenException.class);
        assertThatThrownBy(() -> service.requireManage("skill-team", actor("stranger", "maintainer")))
                .isInstanceOf(SkillManageForbiddenException.class);
    }

    @Test
    void authoritativeTeamClaimsControlVisibilityWithoutGrantingManagement() {
        TestFixture fixture = fixture();
        fixture.addVersion(version("pkg-claimed-team", "skill-claimed-team", "1.0.0", "published", "owner",
                NOW.minusSeconds(30)));
        fixture.configure(config(
                List.of(
                        team("team-a", List.of("local-member"), "active"),
                        team("team-inactive", List.of(), "inactive")),
                List.of(binding("local-member", "viewer", "team-a", "active"))));
        fixture.scopeStore().create(scope("skill-claimed-team", SkillVisibility.TEAM, "team-a", List.of(), 1));

        SkillAuthorizationService service = fixture.service();
        Actor claimedMember = new Actor("external-user", "viewer", Set.of("team-a"), true);
        Actor emptyClaim = new Actor("local-member", "viewer", Set.of(), true);
        Actor inactiveClaim = new Actor("external-user", "viewer", Set.of("team-inactive"), true);

        service.requireVisible("skill-claimed-team", claimedMember, SkillVisibilityContext.CATALOG);

        assertThatThrownBy(() -> service.requireVisible("skill-claimed-team", emptyClaim,
                SkillVisibilityContext.CATALOG)).isInstanceOf(SkillNotVisibleException.class);
        assertThatThrownBy(() -> service.requireVisible("skill-claimed-team", inactiveClaim,
                SkillVisibilityContext.CATALOG)).isInstanceOf(SkillNotVisibleException.class);
        assertThatThrownBy(() -> service.requireManage("skill-claimed-team", claimedMember))
                .isInstanceOf(SkillManageForbiddenException.class);
    }

    @Test
    void restrictedScopeAllowsOnlyExplicitMaintainersAndIgnoresTeamMembershipForVisibility() {
        TestFixture fixture = fixture();
        fixture.addVersion(version("pkg-restricted", "skill-restricted", "1.0.0", "published", "owner",
                NOW.minusSeconds(30)));
        fixture.configure(config(
                List.of(team("team-a", List.of("team-maintainer"), "active")),
                List.of(binding("team-maintainer", "maintainer", "team-a", "active"))));
        fixture.scopeStore().create(scope("skill-restricted", SkillVisibility.RESTRICTED, "", List.of("named-maintainer"), 1));

        SkillAuthorizationService service = fixture.service();

        service.requireVisible("skill-restricted", actor("named-maintainer", "developer"), SkillVisibilityContext.CONTENT);
        service.requireManage("skill-restricted", actor("named-maintainer", "maintainer"));

        assertThatThrownBy(() -> service.requireVisible("skill-restricted", actor("team-maintainer", "maintainer"),
                SkillVisibilityContext.CONTENT)).isInstanceOf(SkillNotVisibleException.class);
        assertThatThrownBy(() -> service.requireManage("skill-restricted", actor("team-maintainer", "maintainer")))
                .isInstanceOf(SkillManageForbiddenException.class);
    }

    @Test
    void reviewerCanReadGovernanceButCannotManageOrSubmit() {
        TestFixture fixture = fixture();
        fixture.addVersion(version("pkg-governance", "skill-governance", "1.0.0", "published", "owner",
                NOW.minusSeconds(30)));
        fixture.scopeStore().create(scope("skill-governance", SkillVisibility.RESTRICTED, "", List.of("owner"), 1));

        SkillAuthorizationService service = fixture.service();

        service.requireVisible("skill-governance", actor("reviewer-1", "reviewer"), SkillVisibilityContext.GOVERNANCE);

        assertThatThrownBy(() -> service.requireManage("skill-governance", actor("reviewer-1", "reviewer")))
                .isInstanceOf(SkillManageForbiddenException.class);
        assertThatThrownBy(() -> service.requireSubmitVersion("skill-governance", actor("reviewer-1", "reviewer")))
                .isInstanceOf(SkillManageForbiddenException.class);
    }

    @Test
    void adminBypassesVisibilityAndManagementChecks() {
        TestFixture fixture = fixture();
        fixture.scopeStore().create(scope("skill-admin-only", SkillVisibility.TEAM, "inactive-team", List.of(), 1));

        SkillAuthorizationService service = fixture.service();

        service.requireVisible("skill-admin-only", actor("admin-1", "admin"), SkillVisibilityContext.CONTENT);
        service.requireManage("skill-admin-only", actor("admin-1", "admin"));
        service.requireSubmitVersion("skill-admin-only", actor("admin-1", "admin"));
    }

    @Test
    void effectiveScopeFallsBackToPublicForHistoricalUnscopedSkill() {
        TestFixture fixture = fixture();
        fixture.addVersion(version("pkg-legacy", "skill-legacy", "1.0.0", "published", "owner",
                NOW.minusSeconds(30)));

        SkillAuthorizationService service = fixture.service();
        SkillScope effective = service.effectiveScope("skill-legacy");

        assertThat(effective.visibility()).isEqualTo(SkillVisibility.PUBLIC);
        assertThat(effective.maintainerUserIds()).containsExactly("owner");
    }

    @Test
    void withdrawnOnlyHistoricalSkillStillResolvesToPublicFallbackForGovernanceReads() {
        TestFixture fixture = fixture();
        fixture.addVersion(version("pkg-withdrawn", "skill-withdrawn", "1.0.0", "withdrawn", "owner",
                NOW.minusSeconds(30)));

        SkillAuthorizationService service = fixture.service();

        SkillScope effective = service.effectiveScope("skill-withdrawn");

        assertThat(effective.visibility()).isEqualTo(SkillVisibility.PUBLIC);
        assertThat(effective.maintainerUserIds()).isEmpty();
        service.requireVisible("skill-withdrawn", actor("reviewer-1", "reviewer"), SkillVisibilityContext.GOVERNANCE);
        service.requireVisible("skill-withdrawn", actor("admin-1", "admin"), SkillVisibilityContext.GOVERNANCE);
    }

    @Test
    void missingSkillsStayMissingAndWithdrawnOnlySkillsDoNotGetManageFallback() {
        TestFixture fixture = fixture();
        fixture.addVersion(version("pkg-withdrawn", "skill-withdrawn", "1.0.0", "withdrawn", "owner",
                NOW.minusSeconds(30)));

        SkillAuthorizationService service = fixture.service();

        assertThatThrownBy(() -> service.effectiveScope("missing-skill"))
                .isInstanceOf(SkillScopeNotFoundException.class);
        assertThatThrownBy(() -> service.requireVisible("missing-skill", actor("alice", "viewer"),
                SkillVisibilityContext.CATALOG)).isInstanceOf(SkillNotVisibleException.class);
        assertThatThrownBy(() -> service.requireVisible("skill-withdrawn", actor("alice", "viewer"),
                SkillVisibilityContext.CATALOG)).isInstanceOf(SkillNotVisibleException.class);
        assertThatThrownBy(() -> service.requireManage("skill-withdrawn", actor("owner", "maintainer")))
                .isInstanceOf(SkillManageForbiddenException.class);
    }

    @Test
    void maintainerCanSubmitTheFirstVersionBeforeAScopeOrVersionExists() {
        TestFixture fixture = fixture();
        SkillAuthorizationService service = fixture.service();

        service.requireSubmitVersion("new-skill", actor("maintainer-1", "maintainer"));
        service.requireSubmitVersion("new-admin-skill", actor("admin-1", "admin"));

        assertThatThrownBy(() -> service.requireSubmitVersion("new-skill", actor("viewer-1", "viewer")))
                .isInstanceOf(SkillManageForbiddenException.class);
    }

    @Test
    void visibleSkillIdsAreStableAndFilteredByAuthorization() {
        TestFixture fixture = fixture();
        fixture.addVersion(version("pkg-z", "skill-z", "1.0.0", "published", "owner-z", NOW.minusSeconds(10)));
        fixture.addVersion(version("pkg-a", "skill-a", "1.0.0", "published", "owner-a", NOW.minusSeconds(20)));
        fixture.addVersion(version("pkg-b", "skill-b", "1.0.0", "published", "owner-b", NOW.minusSeconds(30)));
        fixture.addVersion(version("pkg-withdrawn", "skill-x", "1.0.0", "withdrawn", "owner-x", NOW.minusSeconds(40)));
        fixture.configure(config(
                List.of(team("team-a", List.of("team-user"), "active")),
                List.of(binding("team-user", "viewer", "team-a", "active"))));
        fixture.scopeStore().create(scope("skill-b", SkillVisibility.TEAM, "team-a", List.of(), 1));
        fixture.scopeStore().create(scope("skill-z", SkillVisibility.RESTRICTED, "", List.of("named-maintainer"), 1));

        SkillAuthorizationService service = fixture.service();

        assertThat(service.visibleSkillIds(actor("team-user", "viewer")))
                .containsExactly("skill-a", "skill-b");
        assertThat(service.visibleSkillIds(actor("named-maintainer", "developer")))
                .containsExactly("skill-a", "skill-z");
        assertThat(service.visibleSkillIds(actor("admin-1", "admin")))
                .containsExactly("skill-a", "skill-b", "skill-z");
    }

    @Test
    void updateScopeValidatesActiveTeamAndSupportsFirstCreateWithoutExistingVersion() {
        TestFixture fixture = fixture();
        fixture.configure(config(List.of(team("team-a", List.of("member"), "active")), List.of()));

        SkillAuthorizationService service = fixture.service();
        assertThatThrownBy(() -> service.updateScope("new-skill",
                new SkillScopeMutation(SkillVisibility.TEAM, "team-a", List.of(), 3, "admin-1", "admin-1"),
                actor("admin-1", "admin"), "req-conflict")).isInstanceOf(SkillScopeConflictException.class);

        SkillScope created = service.updateScope("new-skill",
                new SkillScopeMutation(SkillVisibility.TEAM, "team-a", List.of(), 0, "admin-1", "admin-1"),
                actor("admin-1", "admin"), "req-1");

        assertThat(created.revision()).isEqualTo(1);
        assertThat(created.ownerTeamId()).isEqualTo("team-a");

        assertThatThrownBy(() -> service.updateScope("bad-team-skill",
                new SkillScopeMutation(SkillVisibility.TEAM, "missing-team", List.of(), 0, "admin-1", "admin-1"),
                actor("admin-1", "admin"), "req-2")).isInstanceOf(SkillScopeInvalidException.class);
    }

    private TestFixture fixture() {
        return new TestFixture(tempDir, NOW);
    }

    private Actor actor(String userId, String role) {
        return new Actor(userId, role);
    }

    private SkillScope scope(String skillId, SkillVisibility visibility, String ownerTeamId,
                             List<String> maintainerUserIds, int revision) {
        return new SkillScope(skillId, visibility, ownerTeamId, maintainerUserIds, revision,
                "scope-admin", NOW.minusSeconds(300), "scope-admin", NOW.minusSeconds(300));
    }

    private GovernanceConfiguration config(List<TeamDefinition> teams, List<RoleBinding> bindings) {
        return new GovernanceConfiguration(teams, bindings, List.of(), List.of(), List.of(), null);
    }

    private TeamDefinition team(String teamId, List<String> members, String status) {
        return new TeamDefinition(teamId, teamId, "", members.isEmpty() ? "" : members.get(0),
                members, status, NOW.minusSeconds(600), NOW.minusSeconds(600));
    }

    private RoleBinding binding(String userId, String role, String teamId, String status) {
        return new RoleBinding(userId, role, teamId, status, "admin", NOW.minusSeconds(600));
    }

    private SkillVersion version(String packageId, String skillId, String version, String status,
                                 String uploadedBy, Instant uploadedAt) {
        return new SkillVersion(packageId, skillId, version, status, "a".repeat(64), 1L, "",
                uploadedBy, uploadedAt, "reviewer", uploadedAt, "review-" + packageId, null, null, null, null, "low");
    }

    private static final class TestFixture {
        private final GovernanceStore governanceStore;
        private final SkillScopeStore scopeStore;
        private final Clock clock;

        private TestFixture(Path tempDir, Instant now) {
            governanceStore = new GovernanceStore(tempDir.resolve("governance-" + System.nanoTime() + ".json"), List.of());
            scopeStore = new SkillScopeStore(tempDir.resolve("scopes-" + System.nanoTime() + ".json"),
                    new ObjectMapper().findAndRegisterModules());
            clock = Clock.fixed(now, ZoneOffset.UTC);
        }

        private void addVersion(SkillVersion version) {
            governanceStore.createPendingVersion(version,
                    new com.huawei.skillcenter.governance.ReviewTask("review-" + version.packageId(),
                            version.packageId(), version.skillId(), version.version(), "approved",
                            version.uploadedBy(), version.uploadedAt(), "reviewer", version.uploadedAt(), null),
                    new com.huawei.skillcenter.governance.AuditEvent("audit-" + version.packageId(),
                            "PACKAGE_IMPORTED", "SKILL_VERSION", version.packageId(), "system", "admin", "seed",
                            version.uploadedAt(), Map.of("skillId", version.skillId())));
        }

        private void configure(GovernanceConfiguration configuration) {
            governanceStore.updateGovernanceConfiguration(configuration, null);
        }

        private SkillAuthorizationService service() {
            return new SkillAuthorizationService(scopeStore, governanceStore, clock);
        }

        private SkillScopeStore scopeStore() {
            return scopeStore;
        }
    }
}
