package com.huawei.skillcenter.relationship;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationRecord;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.release.ReleaseEnvironment;
import com.huawei.skillcenter.release.ReleaseGateSnapshot;
import com.huawei.skillcenter.release.ReleaseRecord;
import com.huawei.skillcenter.release.ReleaseRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillRelationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");

    @TempDir
    Path tempDir;

    private GovernanceStore governance;
    private SkillRelationStore relations;
    private ReleaseRecordStore releases;
    private SkillRelationService service;

    @BeforeEach
    void setUp() {
        governance = mock(GovernanceStore.class);
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(
                List.of(version("skill-a", "1.0.0", "a"), version("skill-b", "2.0.0", "b"),
                        version("skill-c", "3.0.0", "c")),
                List.of(), List.of(
                        installation("a-1", "skill-a", "1.0.0", "installed"),
                        installation("a-2", "skill-a", "1.0.0", "installing"),
                        installation("a-3", "skill-a", "1.0.0", "removed")), List.of()));
        relations = new SkillRelationStore(tempDir.resolve("relations.json"), mapper());
        releases = mock(ReleaseRecordStore.class);
        when(releases.findAll("skill-a", "1.0.0", ReleaseEnvironment.PRODUCTION, null))
                .thenReturn(List.of(ReleaseRecord.request("release-a", "skill-a", "1.0.0", "a".repeat(64),
                                ReleaseEnvironment.PRODUCTION, ReleaseGateSnapshot.passed(NOW), "release-a-idem", "owner", NOW)
                        .approve("admin", NOW.plusSeconds(1))
                        .promoting("mock/skill-a", NOW.plusSeconds(2))
                        .promoted("mock/skill-a", NOW.plusSeconds(3))));
        service = new SkillRelationService(governance, relations, releases,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsRelationAndReportsDirectAndTransitiveDownstreamImpact() {
        service.create(request("skill-a", "1.0.0", "skill-b", "2.0.0", SkillRelationType.DEPENDS_ON),
                admin(), "create-a");
        service.create(request("skill-c", "3.0.0", "skill-a", "1.0.0", SkillRelationType.COMPOSES),
                admin(), "create-c");

        SkillRelationImpact impact = service.impact("skill-b", "2.0.0",
                new SkillRelationQuery(null, null, null, null, null, 5, 100), new Actor("reviewer", "reviewer"));

        assertThat(impact.truncated()).isFalse();
        assertThat(impact.nodes()).extracting(SkillRelationImpactNode::skillId)
                .containsExactly("skill-a", "skill-c");
        assertThat(impact.nodes().get(0).depth()).isEqualTo(1);
        assertThat(impact.nodes().get(0).productionPromoted()).isTrue();
        assertThat(impact.nodes().get(0).activeInstallationCount()).isEqualTo(2);
        assertThat(impact.nodes().get(1).depth()).isEqualTo(2);
    }

    @Test
    void rejectsSelfEdgeAndDirectCycleWithoutPersistingInvalidRelation() {
        assertThatThrownBy(() -> service.create(
                request("skill-a", "1.0.0", "skill-a", "1.0.0", SkillRelationType.DEPENDS_ON), admin(), "self"))
                .isInstanceOf(SkillRelationConflictException.class);
        service.create(request("skill-a", "1.0.0", "skill-b", "2.0.0", SkillRelationType.DEPENDS_ON),
                admin(), "first");

        assertThatThrownBy(() -> service.create(
                request("skill-b", "2.0.0", "skill-a", "1.0.0", SkillRelationType.DEPENDS_ON), admin(), "cycle"))
                .isInstanceOf(SkillRelationCycleException.class);
        assertThat(relations.findAll(null, null, null, null, SkillRelationStatus.ACTIVE)).hasSize(1);
    }

    @Test
    void respectsDepthLimitAndMarksTruncatedTraversal() {
        service.create(request("skill-a", "1.0.0", "skill-b", "2.0.0", SkillRelationType.DEPENDS_ON),
                admin(), "create-a");
        service.create(request("skill-c", "3.0.0", "skill-a", "1.0.0", SkillRelationType.COMPOSES),
                admin(), "create-c");

        SkillRelationImpact impact = service.impact("skill-b", "2.0.0",
                new SkillRelationQuery(null, null, null, null, null, 1, 100), admin());

        assertThat(impact.truncated()).isTrue();
        assertThat(impact.nodes()).extracting(SkillRelationImpactNode::skillId).containsExactly("skill-a");
    }

    @Test
    void retiringRelationRemovesItFromImpactAndRequiresAdmin() {
        SkillRelation relation = service.create(
                request("skill-a", "1.0.0", "skill-b", "2.0.0", SkillRelationType.REPLACES), admin(), "create");

        SkillRelation retired = service.retire(relation.relationId(), "migration complete", admin(), "retire");

        assertThat(retired.status()).isEqualTo(SkillRelationStatus.RETIRED);
        assertThat(service.impact("skill-b", "2.0.0", SkillRelationQuery.defaults(), admin()).nodes()).isEmpty();
        assertThatThrownBy(() -> service.retire(relation.relationId(), "again", admin(), "retire-again"))
                .isInstanceOf(SkillRelationConflictException.class);
        assertThatThrownBy(() -> service.create(
                request("skill-c", "3.0.0", "skill-b", "2.0.0", SkillRelationType.DEPENDS_ON),
                new Actor("reviewer", "reviewer"), "reviewer-create"))
                .isInstanceOf(com.huawei.skillcenter.governance.ForbiddenException.class);
    }

    private SkillRelationRequest request(String sourceSkillId, String sourceVersion,
                                         String targetSkillId, String targetVersion, SkillRelationType type) {
        return new SkillRelationRequest(sourceSkillId, sourceVersion, targetSkillId, targetVersion, type);
    }

    private SkillVersion version(String skillId, String version, String shaPrefix) {
        return new SkillVersion("package-" + skillId, skillId, version, "published", shaPrefix.repeat(64),
                10, "", "owner", NOW, "owner", NOW, "review-" + skillId);
    }

    private InstallationRecord installation(String id, String skillId, String version, String status) {
        return new InstallationRecord(id, "manifest-" + id, skillId, version, "codex", "1.0.0",
                "user-" + id, status, NOW, NOW);
    }

    private Actor admin() {
        return new Actor("admin", "admin");
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
