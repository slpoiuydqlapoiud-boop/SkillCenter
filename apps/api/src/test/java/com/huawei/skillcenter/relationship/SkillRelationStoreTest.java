package com.huawei.skillcenter.relationship;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillRelationStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void createsNormalizesAndRestoresVersionRelation() {
        SkillRelationStore store = store();
        SkillRelation relation = SkillRelation.create(" relation-1 ", " skill-a ", " 1.0.0 ",
                "skill-b", "2.0.0", SkillRelationType.DEPENDS_ON, "admin", NOW);

        store.create(relation);
        SkillRelation restored = new SkillRelationStore(tempDir.resolve("relations.json"), mapper())
                .find("relation-1").orElseThrow();

        assertThat(restored.sourceSkillId()).isEqualTo("skill-a");
        assertThat(restored.sourceVersion()).isEqualTo("1.0.0");
        assertThat(restored.status()).isEqualTo(SkillRelationStatus.ACTIVE);
        assertThat(restored.declaredBy()).isEqualTo("admin");
    }

    @Test
    void rejectsDuplicateActiveBusinessKeyButAllowsRetiredHistory() {
        SkillRelationStore store = store();
        SkillRelation first = relation("relation-1", SkillRelationType.COMPOSES);
        store.create(first);

        assertThatThrownBy(() -> store.create(relation("relation-2", SkillRelationType.COMPOSES)))
                .isInstanceOf(SkillRelationConflictException.class);

        store.replace(first.retire("admin", "replaced", NOW.plusSeconds(1)));
        SkillRelation second = relation("relation-2", SkillRelationType.COMPOSES);
        store.create(second);

        assertThat(store.findAll("skill-a", "1.0.0", "skill-b", "2.0.0", null)).hasSize(2);
        assertThat(store.findAll("skill-a", "1.0.0", "skill-b", "2.0.0", SkillRelationStatus.ACTIVE))
                .extracting(SkillRelation::relationId).containsExactly("relation-2");
    }

    @Test
    void retirementPreservesImmutableRelationContext() {
        SkillRelation relation = relation("relation-1", SkillRelationType.REPLACES);
        SkillRelation retired = relation.retire("admin", "migration complete", NOW.plusSeconds(1));

        assertThat(retired.status()).isEqualTo(SkillRelationStatus.RETIRED);
        assertThat(retired.relationId()).isEqualTo(relation.relationId());
        assertThat(retired.sourceSkillId()).isEqualTo(relation.sourceSkillId());
        assertThat(retired.targetVersion()).isEqualTo(relation.targetVersion());
        assertThat(retired.retiredBy()).isEqualTo("admin");
        assertThat(retired.statusReason()).isEqualTo("migration complete");
    }

    @Test
    void replacementCannotChangeRelationContext() {
        SkillRelationStore store = store();
        SkillRelation relation = relation("relation-1", SkillRelationType.DEPENDS_ON);
        store.create(relation);
        SkillRelation changed = SkillRelation.create("relation-1", "skill-a", "1.0.0", "skill-c", "2.0.0",
                SkillRelationType.DEPENDS_ON, "admin", NOW);

        assertThatThrownBy(() -> store.replace(changed))
                .isInstanceOf(SkillRelationConflictException.class);
    }

    private SkillRelationStore store() {
        return new SkillRelationStore(tempDir.resolve("relations.json"), mapper());
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private SkillRelation relation(String id, SkillRelationType type) {
        return SkillRelation.create(id, "skill-a", "1.0.0", "skill-b", "2.0.0", type, "admin", NOW);
    }
}
