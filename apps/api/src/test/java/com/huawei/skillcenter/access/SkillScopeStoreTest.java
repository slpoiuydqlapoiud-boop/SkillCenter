package com.huawei.skillcenter.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillScopeStoreTest {
    private static final Instant DECLARED = Instant.parse("2026-08-24T01:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-24T01:05:00Z");
    private static final String DECLARED_BY = "admin-creator";
    private static final String UPDATED_BY = "admin-updater";

    @TempDir
    Path tempDir;

    @Test
    void loadsMissingStateAsEmpty() {
        SkillScopeStore store = store(tempDir.resolve("skill-scopes.json"));

        assertThat(store.find("missing-skill")).isEmpty();
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void createsNormalizedScopesWithStableOrderingAndRestartRecovery() {
        Path state = tempDir.resolve("skill-scopes.json");
        SkillScopeStore store = store(state);

        store.create(scope(" skill-b ", SkillVisibility.TEAM, " team-2 ",
                List.of(" user-2 ", "user-1", "user-2"), 1, DECLARED, UPDATED));
        store.create(scope("skill-a", SkillVisibility.PUBLIC, "",
                List.of(" maint-3 ", "maint-1"), 1, DECLARED.plusSeconds(1), UPDATED.plusSeconds(1)));

        assertThat(store.findAll()).extracting(SkillScope::skillId)
                .containsExactly("skill-a", "skill-b");

        SkillScope restored = store(state).find("skill-b").orElseThrow();
        assertThat(restored.declaredBy()).isEqualTo(DECLARED_BY);
        assertThat(restored.updatedBy()).isEqualTo(UPDATED_BY);
        assertThat(restored.ownerTeamId()).isEqualTo("team-2");
        assertThat(restored.maintainerUserIds()).containsExactly("user-1", "user-2");
        assertThat(restored.visibility()).isEqualTo(SkillVisibility.TEAM);
    }

    @Test
    void rejectsDuplicateSkillIdsOnCreate() {
        SkillScopeStore store = store(tempDir.resolve("skill-scopes.json"));
        store.create(scope("skill-a", SkillVisibility.PUBLIC, "", List.of(), 1, DECLARED, UPDATED));

        assertThatThrownBy(() -> store.create(scope("skill-a", SkillVisibility.TEAM, "team-1",
                List.of("user-1"), 1, DECLARED.plusSeconds(1), UPDATED.plusSeconds(1))))
                .isInstanceOf(SkillScopeConflictException.class)
                .hasMessageContaining("skillId");
    }

    @Test
    void rejectsInvalidVisibilityAndOwnershipRules() {
        assertThatThrownBy(() -> scope("skill-a", null, "", List.of(), 1, DECLARED, UPDATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visibility");
        assertThatThrownBy(() -> scope("skill-a", SkillVisibility.PUBLIC, "", List.of(), 1,
                " ", UPDATED_BY, DECLARED, UPDATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declaredBy");
        assertThatThrownBy(() -> scope("skill-a", SkillVisibility.PUBLIC, "", List.of(), 1,
                DECLARED_BY, "", DECLARED, UPDATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updatedBy");
        assertThatThrownBy(() -> new SkillScopeMutation(SkillVisibility.PUBLIC, "", List.of(), 1,
                " ", UPDATED_BY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declaredBy");
        assertThatThrownBy(() -> scope("skill-a", SkillVisibility.TEAM, "", List.of(), 1, DECLARED, UPDATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerTeamId");
        assertThatThrownBy(() -> scope("skill-a", SkillVisibility.RESTRICTED, "", List.of(), 1, DECLARED, UPDATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maintainerUserIds");
    }

    @Test
    void teamScopeValidationRemainsStructuralAtStoreBoundary() {
        SkillScope scope = scope("skill-team", SkillVisibility.TEAM, "team-not-yet-validated",
                List.of("user-1"), 1, DECLARED, UPDATED);

        assertThat(scope.ownerTeamId()).isEqualTo("team-not-yet-validated");
    }

    @Test
    void restartRecoveryRejectsDuplicatePersistedSkillIds() throws Exception {
        Path state = tempDir.resolve("duplicate-skill-scopes.json");
        Files.writeString(state, """
                [
                  {
                    "skillId": "skill-a",
                    "visibility": "PUBLIC",
                    "ownerTeamId": "",
                    "maintainerUserIds": [],
                    "revision": 1,
                    "declaredBy": "admin-creator",
                    "declaredAt": "2026-08-24T01:00:00Z",
                    "updatedBy": "admin-updater",
                    "updatedAt": "2026-08-24T01:05:00Z"
                  },
                  {
                    "skillId": "skill-a",
                    "visibility": "TEAM",
                    "ownerTeamId": "team-2",
                    "maintainerUserIds": [
                      "user-1"
                    ],
                    "revision": 1,
                    "declaredBy": "admin-creator",
                    "declaredAt": "2026-08-24T01:00:01Z",
                    "updatedBy": "admin-updater",
                    "updatedAt": "2026-08-24T01:05:01Z"
                  }
                ]
                """);

        assertThatThrownBy(() -> store(state))
                .isInstanceOf(SkillScopePersistenceException.class);
    }

    @Test
    void restartRecoveryRejectsUnexpectedSensitiveFields() throws Exception {
        Path state = tempDir.resolve("sensitive-skill-scopes.json");
        Files.writeString(state, """
                [
                  {
                    "skillId": "skill-a",
                    "visibility": "RESTRICTED",
                    "ownerTeamId": "",
                    "maintainerUserIds": [
                      "user-1"
                    ],
                    "revision": 1,
                    "declaredBy": "admin-creator",
                    "declaredAt": "2026-08-24T01:00:00Z",
                    "updatedBy": "admin-updater",
                    "updatedAt": "2026-08-24T01:05:00Z",
                    "token": "secret"
                  }
                ]
                """);

        assertThatThrownBy(() -> store(state))
                .isInstanceOf(SkillScopePersistenceException.class);
    }

    @Test
    void replaceRejectsStaleRevision() {
        SkillScopeStore store = store(tempDir.resolve("skill-scopes.json"));
        store.create(scope("skill-a", SkillVisibility.PUBLIC, "", List.of(), 1, DECLARED, UPDATED));

        assertThatThrownBy(() -> store.replace(scope("skill-a", SkillVisibility.TEAM, "team-1",
                List.of("user-1"), 1, DECLARED_BY, "reviewer-2", DECLARED, UPDATED.plusSeconds(10)), 0))
                .isInstanceOf(SkillScopeConflictException.class)
                .hasMessageContaining("revision");
        assertThat(store.find("skill-a").orElseThrow().revision()).isEqualTo(1);
    }

    @Test
    void replaceIncrementsRevisionAndPersistsReplacement() {
        Path state = tempDir.resolve("skill-scopes.json");
        SkillScopeStore store = store(state);
        store.create(scope("skill-a", SkillVisibility.PUBLIC, "", List.of(), 1, DECLARED, UPDATED));

        SkillScope replaced = store.replace(scope("skill-a", SkillVisibility.RESTRICTED, "",
                List.of("user-2", " user-1 "), 99, "different-actor", "security-admin",
                DECLARED.minusSeconds(10), UPDATED.plusSeconds(10)), 1);

        assertThat(replaced.revision()).isEqualTo(2);
        assertThat(replaced.declaredBy()).isEqualTo(DECLARED_BY);
        assertThat(replaced.updatedBy()).isEqualTo("security-admin");
        assertThat(replaced.declaredAt()).isEqualTo(DECLARED);
        assertThat(replaced.updatedAt()).isEqualTo(UPDATED.plusSeconds(10));
        assertThat(replaced.maintainerUserIds()).containsExactly("user-1", "user-2");
        assertThat(state.resolveSibling("skill-scopes.json.tmp")).doesNotExist();
        assertThat(store(state).find("skill-a")).contains(replaced);
    }

    private SkillScopeStore store(Path statePath) {
        return new SkillScopeStore(statePath, mapper());
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private SkillScope scope(String skillId, SkillVisibility visibility, String ownerTeamId,
                             List<String> maintainerUserIds, int revision, Instant declaredAt, Instant updatedAt) {
        return scope(skillId, visibility, ownerTeamId, maintainerUserIds, revision,
                DECLARED_BY, UPDATED_BY, declaredAt, updatedAt);
    }

    private SkillScope scope(String skillId, SkillVisibility visibility, String ownerTeamId,
                             List<String> maintainerUserIds, int revision, String declaredBy, String updatedBy,
                             Instant declaredAt, Instant updatedAt) {
        return new SkillScope(skillId, visibility, ownerTeamId, maintainerUserIds, revision,
                declaredBy, declaredAt, updatedBy, updatedAt);
    }
}
