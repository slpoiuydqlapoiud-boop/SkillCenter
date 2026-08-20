package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernanceConfigurationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void adminCanConfigureTeamTaxonomyCollectionAndPolicyWithAudits() {
        GovernanceStore store = storeWithPublishedSkill();
        GovernanceConfigurationService service = new GovernanceConfigurationService(store);
        Actor admin = new Actor("admin-1", "admin");

        service.upsertTeam(new TeamMutation("team-a", "AI Platform", "", "owner-1", List.of("owner-1")), admin, "req-1");
        service.upsertCategory(new TaxonomyMutation("automation", "Automation", "", 1), admin, "req-2");
        service.upsertTag(new TaxonomyMutation("internal", "Internal", "", 1), admin, "req-3");
        CollectionDefinition collection = service.upsertCollection(
                new CollectionMutation("starter", "Starter", "", null, "public", List.of(), 1), admin, "req-4");
        collection = service.addSkill(collection.collectionId(), "demo", admin, "req-5");
        PlatformPolicy policy = service.updatePolicy(new PolicyMutation(List.of(12, 24), 24, "2.1.0", "public"),
                admin, "req-6");

        assertThat(collection.skillIds()).containsExactly("demo");
        assertThat(policy.policyVersion()).isEqualTo(2);
        assertThat(store.snapshot().configuration().categories()).extracting(CategoryDefinition::code)
                .containsExactly("automation");
        assertThat(store.snapshot().audits()).extracting(AuditEvent::action)
                .contains("TEAM_CREATED", "CATEGORY_CREATED", "TAG_CREATED", "COLLECTION_SKILL_ADDED",
                        "PLATFORM_POLICY_UPDATED");
    }

    @Test
    void nonAdminCannotMutateAndTeamVisibilityIsScoped() {
        GovernanceStore store = storeWithPublishedSkill();
        GovernanceConfigurationService service = new GovernanceConfigurationService(store);
        Actor admin = new Actor("admin-1", "admin");
        service.upsertTeam(new TeamMutation("team-a", "AI Platform", "", "owner-1", List.of("owner-1")), admin, "req-1");
        service.upsertRoleBinding("owner-1", new RoleBindingMutation("maintainer", "team-a"), admin, "req-2");
        service.upsertCollection(new CollectionMutation("team-kit", "Team Kit", "", "team-a", "team",
                List.of("demo"), 1), admin, "req-3");

        assertThatThrownBy(() -> service.upsertTag(new TaxonomyMutation("x", "X", "", 1),
                new Actor("viewer-1", "viewer"), "req-4")).isInstanceOf(ForbiddenException.class);
        assertThat(service.read(new Actor("owner-1", "maintainer")).collections())
                .extracting(CollectionDefinition::collectionId).containsExactly("team-kit");
        assertThat(service.read(new Actor("outsider", "viewer")).collections()).isEmpty();
    }

    @Test
    void rejectsInvalidPolicyAndWithdrawnSkill() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("invalid.json"), List.of());
        store.createPendingVersion(new SkillVersion("p1", "withdrawn", "1.0.0", "withdrawn", "a".repeat(64), 1,
                "", "owner", Instant.now(), "reviewer", Instant.now(), "r1"),
                new ReviewTask("r1", "p1", "withdrawn", "1.0.0", "approved", "owner", Instant.now(), "reviewer",
                        Instant.now(), null),
                new AuditEvent("a1", "PACKAGE_APPROVED", "SKILL_VERSION", "p1", "reviewer", "reviewer", "req",
                        Instant.now(), Map.of()));
        GovernanceConfigurationService service = new GovernanceConfigurationService(store);

        assertThatThrownBy(() -> service.updatePolicy(new PolicyMutation(List.of(24, 12), 24, "bad", "public"),
                new Actor("admin-1", "admin"), "req-1")).isInstanceOf(InvalidLifecycleRequestException.class);
        assertThatThrownBy(() -> service.upsertCollection(new CollectionMutation("bad", "Bad", "", null, "public",
                List.of("withdrawn"), 0), new Actor("admin-1", "admin"), "req-2"))
                .isInstanceOf(InvalidLifecycleRequestException.class);
    }

    private GovernanceStore storeWithPublishedSkill() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-" + System.nanoTime() + ".json"), List.of());
        store.createPendingVersion(new SkillVersion("p1", "demo", "1.0.0", "published", "a".repeat(64), 1,
                "", "owner", Instant.now(), "reviewer", Instant.now(), "r1"),
                new ReviewTask("r1", "p1", "demo", "1.0.0", "approved", "owner", Instant.now(), "reviewer",
                        Instant.now(), null),
                new AuditEvent("a1", "PACKAGE_APPROVED", "SKILL_VERSION", "p1", "reviewer", "reviewer", "req",
                        Instant.now(), Map.of()));
        return store;
    }
}
