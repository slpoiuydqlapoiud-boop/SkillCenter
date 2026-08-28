package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.access.SkillScopeStore;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.release.ReleaseEnvironment;
import com.huawei.skillcenter.release.ReleaseRecordStore;
import com.huawei.skillcenter.release.ReleaseStatus;
import com.huawei.skillcenter.relationship.SkillRelationStore;
import com.huawei.skillcenter.relationship.SkillRelationStatus;
import com.huawei.skillcenter.relationship.SkillRelationType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSkillLifecycleProjectionStoreTest {
    private static final Instant AT = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void findImpactStopsAtNodeLimitAndDoesNotTraversePastTheBoundary() {
        JsonSkillLifecycleProjectionStore store = new JsonSkillLifecycleProjectionStore(
                source(buildWideImpactInput()),
                Clock.fixed(AT, ZoneOffset.UTC));

        SkillLifecycleImpactView impact = store.findImpact("root-skill", "1.0.0");

        assertThat(impact.rootSkillId()).isEqualTo("root-skill");
        assertThat(impact.rootVersion()).isEqualTo("1.0.0");
        assertThat(impact.truncated()).isTrue();
        assertThat(impact.nodes()).hasSize(100);
        assertThat(impact.nodes())
                .extracting(SkillLifecycleImpactView.Node::skillId)
                .contains("downstream-001", "downstream-050", "downstream-100")
                .doesNotContain("downstream-101");
    }

    @Test
    void findImpactMarksDepthTruncationWithoutFollowingMissingVersionsPastDepthEight() {
        JsonSkillLifecycleProjectionStore store = new JsonSkillLifecycleProjectionStore(
                source(buildDeepImpactInput()),
                Clock.fixed(AT, ZoneOffset.UTC));

        SkillLifecycleImpactView impact = store.findImpact("root-skill", "1.0.0");

        assertThat(impact.rootSkillId()).isEqualTo("root-skill");
        assertThat(impact.rootVersion()).isEqualTo("1.0.0");
        assertThat(impact.truncated()).isTrue();
        assertThat(impact.nodes()).hasSize(8);
        assertThat(impact.nodes())
                .extracting(SkillLifecycleImpactView.Node::depth)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(impact.nodes())
                .extracting(SkillLifecycleImpactView.Node::skillId)
                .containsExactly(
                        "depth-skill-01",
                        "depth-skill-02",
                        "depth-skill-03",
                        "depth-skill-04",
                        "depth-skill-05",
                        "depth-skill-06",
                        "depth-skill-07",
                        "depth-skill-08");
    }

    private static SkillLifecycleProjectionSource source(SkillLifecycleProjectionInput input) {
        return new SkillLifecycleProjectionSource(
                new GovernanceStore(Path.of("json-store-governance.json"), List.of()),
                new ReleaseRecordStore(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), "json-store-releases.json"),
                new SkillScopeStore(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), "json-store-scopes.json"),
                new SkillRelationStore(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), "json-store-relations.json"),
                Clock.fixed(AT, ZoneOffset.UTC)) {
            @Override
            public SkillLifecycleProjectionInput read() {
                return input;
            }
        };
    }

    private static SkillLifecycleProjectionInput buildWideImpactInput() {
        List<SkillLifecycleVersionRow> versions = new ArrayList<>();
        List<SkillLifecycleReleaseRow> releases = new ArrayList<>();
        List<SkillLifecycleRelationRow> relations = new ArrayList<>();
        versions.add(version("root-skill", "1.0.0", "a".repeat(64)));
        for (int index = 1; index <= 100; index++) {
            String skillId = "downstream-%03d".formatted(index);
            versions.add(version(skillId, "1.0.0", hashFor(index)));
            releases.add(release("release-%03d".formatted(index), skillId, "1.0.0", hashFor(index)));
            relations.add(relation("relation-%03d".formatted(index), skillId, "1.0.0", "root-skill", "1.0.0"));
        }
        relations.add(new SkillLifecycleRelationRow(
                "relation-101",
                "downstream-101",
                "1.0.0",
                "root-skill",
                "1.0.0",
                SkillRelationType.DEPENDS_ON,
                SkillRelationStatus.ACTIVE,
                AT,
                null));
        return new SkillLifecycleProjectionInput(
                List.of(),
                List.copyOf(versions),
                List.copyOf(releases),
                List.of(),
                List.copyOf(relations));
    }

    private static SkillLifecycleProjectionInput buildDeepImpactInput() {
        List<SkillLifecycleVersionRow> versions = new ArrayList<>();
        List<SkillLifecycleReleaseRow> releases = new ArrayList<>();
        List<SkillLifecycleRelationRow> relations = new ArrayList<>();
        versions.add(version("root-skill", "1.0.0", "a".repeat(64)));
        String targetSkillId = "root-skill";
        String targetVersion = "1.0.0";
        for (int depth = 1; depth <= 8; depth++) {
            String skillId = "depth-skill-%02d".formatted(depth);
            String hash = hashFor(depth);
            versions.add(version(skillId, "1.0.0", hash));
            releases.add(release("release-depth-%02d".formatted(depth), skillId, "1.0.0", hash));
            relations.add(relation("relation-depth-%02d".formatted(depth), skillId, "1.0.0", targetSkillId, targetVersion));
            targetSkillId = skillId;
        }
        relations.add(new SkillLifecycleRelationRow(
                "relation-depth-09",
                "depth-skill-09",
                "1.0.0",
                targetSkillId,
                targetVersion,
                SkillRelationType.DEPENDS_ON,
                SkillRelationStatus.ACTIVE,
                AT,
                null));
        return new SkillLifecycleProjectionInput(
                List.of(),
                List.copyOf(versions),
                List.copyOf(releases),
                List.of(),
                List.copyOf(relations));
    }

    private static SkillLifecycleVersionRow version(String skillId, String version, String sha256) {
        return new SkillLifecycleVersionRow(
                skillId,
                version,
                "pkg-" + skillId,
                "PUBLISHED",
                sha256,
                32L,
                "uploader",
                AT,
                AT,
                "LOW");
    }

    private static SkillLifecycleReleaseRow release(String releaseId, String skillId, String version, String sha256) {
        return new SkillLifecycleReleaseRow(
                releaseId,
                skillId,
                version,
                ReleaseEnvironment.PRODUCTION,
                ReleaseStatus.PROMOTED,
                "PASSED",
                sha256,
                AT,
                AT,
                AT,
                "assessment-" + releaseId,
                "",
                "",
                "");
    }

    private static SkillLifecycleRelationRow relation(String relationId,
                                                      String sourceSkillId,
                                                      String sourceVersion,
                                                      String targetSkillId,
                                                      String targetVersion) {
        return new SkillLifecycleRelationRow(
                relationId,
                sourceSkillId,
                sourceVersion,
                targetSkillId,
                targetVersion,
                SkillRelationType.DEPENDS_ON,
                SkillRelationStatus.ACTIVE,
                AT,
                null);
    }

    private static String hashFor(int value) {
        return "%064x".formatted(value);
    }
}
