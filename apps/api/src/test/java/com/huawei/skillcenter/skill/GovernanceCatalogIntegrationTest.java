package com.huawei.skillcenter.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.CollectionMutation;
import com.huawei.skillcenter.governance.CollectionDefinition;
import com.huawei.skillcenter.governance.GovernanceConfigurationService;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleBindingMutation;
import com.huawei.skillcenter.governance.TeamMutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernanceCatalogIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void publicAndTeamCollectionVisibilityUsesGovernanceAndResolvesSkills() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        SkillRecord skill = new SkillRecord("demo", "Demo", "1.0.0", "desc", "legacy", List.of(), "low", "low",
                "team-a", "owner", "", "blue", "published", "2026-08-18", "2026-08-18", "Java", "Java", "",
                List.of(), List.of(), List.of(), "", "", "", List.of(), new SkillMetrics(0, 0, 0, 0, 0));
        SkillRepository repository = new SkillRepository() {
            @Override public PageResult<SkillRecord> findPublished(SkillQuery query) { return new PageResult<>(List.of(skill), 1, query.pageSize(), 1); }
            @Override public Optional<SkillRecord> findDetail(String skillId) { return skill.id().equals(skillId) ? Optional.of(skill) : Optional.empty(); }
        };
        SkillCatalogService catalog = new SkillCatalogService(repository, store, new ObjectMapper());
        GovernanceConfigurationService governance = new GovernanceConfigurationService(store, catalog);
        Actor admin = new Actor("admin", "admin");
        governance.createTeam(new TeamMutation("team-a", "Team A", "", "owner", List.of("owner")), admin, "r1");
        governance.createCollection(new CollectionMutation("public", "Public", "", null, "public", List.of("demo"), 1), admin, "r2");
        governance.createCollection(new CollectionMutation("private", "Private", "", "team-a", "team", List.of("demo"), 2), admin, "r3");
        governance.upsertRoleBinding("owner", new RoleBindingMutation("viewer", "team-a"), admin, "r4");
        CollectionService service = new CollectionService(governance, catalog);

        assertThat(service.list(new Actor("guest", "viewer"), 1, 12)).extracting(CollectionDefinition::collectionId)
                .containsExactly("public");
        assertThat(service.list(new Actor("owner", "viewer"), 1, 12)).extracting(CollectionDefinition::collectionId)
                .containsExactly("public", "private");
        assertThat(service.detail("private", new Actor("owner", "viewer")).skills()).extracting(SkillSummary::id)
                .containsExactly("demo");
        assertThatThrownBy(() -> service.detail("private", new Actor("guest", "viewer")))
                .isInstanceOf(com.huawei.skillcenter.governance.CollectionNotFoundException.class);

        assertThat(service.list(new Actor("owner", "viewer"), 1, 12, "private", "updated"))
                .extracting(CollectionDefinition::collectionId)
                .containsExactly("private");
    }
}
