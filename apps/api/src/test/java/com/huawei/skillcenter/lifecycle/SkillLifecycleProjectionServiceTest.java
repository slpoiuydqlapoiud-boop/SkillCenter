package com.huawei.skillcenter.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillNotVisibleException;
import com.huawei.skillcenter.access.SkillScope;
import com.huawei.skillcenter.access.SkillScopeStore;
import com.huawei.skillcenter.access.SkillVisibility;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.ReviewTask;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import com.huawei.skillcenter.release.ReleaseEnvironment;
import com.huawei.skillcenter.release.ReleaseGateSnapshot;
import com.huawei.skillcenter.release.ReleaseRecord;
import com.huawei.skillcenter.release.ReleaseRecordStore;
import com.huawei.skillcenter.release.ReleaseStatus;
import com.huawei.skillcenter.relationship.SkillRelation;
import com.huawei.skillcenter.relationship.SkillRelationStatus;
import com.huawei.skillcenter.relationship.SkillRelationStore;
import com.huawei.skillcenter.relationship.SkillRelationType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillLifecycleProjectionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T05:00:00Z");
    private static final int MAX_IMPACT_NODES = 100;
    private static final String ZERO_HASH = "0".repeat(64);

    @TempDir
    Path tempDir;

    @Test
    void preflightReadsAllFourJsonFactsWithoutChangingProjection() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("preflight"));
        fixture.addVersion(version("pkg-skill-a", "skill-a", "1.0.0", "published", NOW.minusSeconds(120)));
        fixture.addVersion(version("pkg-skill-b", "skill-b", "2.0.0", "active", NOW.minusSeconds(60)));
        fixture.addRelease(release("release-a", "skill-a", "1.0.0", ReleaseEnvironment.PRODUCTION,
                ReleaseStatus.PROMOTED, "a".repeat(64)));
        fixture.addScope(scope("skill-a", SkillVisibility.PUBLIC, "team-public", List.of("maintainer-a"), 3));
        fixture.addRelation(relation("relation-a", "skill-b", "2.0.0", "skill-a", "1.0.0", SkillRelationStatus.ACTIVE));
        RecordingProjectionRepository repository = new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 7L, ZERO_HASH, 0, 0, 0, 0, 0));
        SkillLifecycleProjectionService service = fixture.service(repository);

        SkillLifecycleProjectionPreflight preflight = service.preflight();

        assertThat(preflight.currentRevision()).isEqualTo(7L);
        assertThat(preflight.matchesCurrentProjection()).isFalse();
        assertThat(preflight.skillCount()).isEqualTo(2);
        assertThat(preflight.versionCount()).isEqualTo(2);
        assertThat(preflight.releaseCount()).isEqualTo(1);
        assertThat(preflight.scopeCount()).isEqualTo(1);
        assertThat(preflight.relationCount()).isEqualTo(1);
        assertThat(repository.status().revision()).isEqualTo(7L);
        assertThat(repository.replacements()).isEmpty();
    }

    @Test
    void reconciliationReportsHealthyImportedProjectionAndZeroCountDrift() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("reconciliation-healthy"));
        fixture.addVersion(version("pkg-skill-a", "skill-a", "1.0.0", "published", NOW.minusSeconds(120)));
        RecordingProjectionRepository repository = new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("postgresql", "2", 3L, ZERO_HASH, 0, 0, 0, 0, 0));
        SkillLifecycleProjectionService service = fixture.service(repository);
        String sourceHash = service.preflight().sourceSha256();
        repository.setStatus(SkillLifecycleProjectionStatus.ready(
                "postgresql", "2", 3L, sourceHash, 1, 1, 0, 0, 0));
        repository.setImportedAt(NOW.minusSeconds(120));

        SkillLifecycleProjectionReconciliation result = service.reconciliation();

        assertThat(result.state()).isEqualTo("HEALTHY");
        assertThat(result.reasonCode()).isEmpty();
        assertThat(result.projectedSourceSha256()).isEqualTo(sourceHash);
        assertThat(result.sourceSha256()).isEqualTo(sourceHash);
        assertThat(result.projectionAgeSeconds()).isEqualTo(120L);
        assertThat(result.sourceCounts()).isEqualTo(new SkillLifecycleProjectionCounts(1, 1, 0, 0, 0));
        assertThat(result.projectedCounts()).isEqualTo(new SkillLifecycleProjectionCounts(1, 1, 0, 0, 0));
        assertThat(result.countDelta()).isEqualTo(new SkillLifecycleProjectionCountDelta(0, 0, 0, 0, 0));
    }

    @Test
    void reconciliationReportsSourceDriftAndCountDifferences() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("reconciliation-drift"));
        fixture.addVersion(version("pkg-skill-a", "skill-a", "1.0.0", "published", NOW.minusSeconds(120)));
        RecordingProjectionRepository repository = new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("postgresql", "2", 3L, ZERO_HASH, 0, 0, 0, 0, 0));
        repository.setImportedAt(NOW.minusSeconds(120));
        SkillLifecycleProjectionService service = fixture.service(repository);

        SkillLifecycleProjectionReconciliation result = service.reconciliation();

        assertThat(result.state()).isEqualTo("DRIFTED");
        assertThat(result.reasonCode()).isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED");
        assertThat(result.countDelta()).isEqualTo(new SkillLifecycleProjectionCountDelta(1, 1, 0, 0, 0));
    }

    @Test
    void reconciliationDetectsCountMismatchEvenWhenSourceHashMatches() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("reconciliation-count-mismatch"));
        fixture.addVersion(version("pkg-skill-a", "skill-a", "1.0.0", "published", NOW.minusSeconds(120)));
        RecordingProjectionRepository repository = new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("postgresql", "2", 3L, ZERO_HASH, 0, 0, 0, 0, 0));
        SkillLifecycleProjectionService service = fixture.service(repository);
        String sourceHash = service.preflight().sourceSha256();
        repository.setStatus(SkillLifecycleProjectionStatus.ready(
                "postgresql", "2", 3L, sourceHash, 0, 0, 0, 0, 0));
        repository.setImportedAt(NOW.minusSeconds(30));

        SkillLifecycleProjectionReconciliation result = service.reconciliation();

        assertThat(result.state()).isEqualTo("DRIFTED");
        assertThat(result.reasonCode()).isEqualTo("SKILL_LIFECYCLE_PROJECTION_COUNT_MISMATCH");
    }

    @Test
    void reconciliationReportsStaleImportedProjectionUsingBoundedAgePolicy() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("reconciliation-stale"));
        fixture.addVersion(version("pkg-skill-a", "skill-a", "1.0.0", "published", NOW.minusSeconds(120)));
        RecordingProjectionRepository repository = new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("postgresql", "2", 3L, ZERO_HASH, 0, 0, 0, 0, 0));
        SkillLifecycleProjectionService service = fixture.service(repository);
        String sourceHash = service.preflight().sourceSha256();
        repository.setStatus(SkillLifecycleProjectionStatus.ready(
                "postgresql", "2", 3L, sourceHash, 1, 1, 0, 0, 0));
        repository.setImportedAt(NOW.minusSeconds(901));

        SkillLifecycleProjectionReconciliation result = service.reconciliation();

        assertThat(result.state()).isEqualTo("STALE");
        assertThat(result.reasonCode()).isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_STALE");
        assertThat(result.projectionAgeSeconds()).isEqualTo(901L);
        assertThat(result.maxProjectionAgeSeconds()).isEqualTo(900L);
    }

    @Test
    void reconciliationTreatsJsonLiveSourceAsNotImportedProjection() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("reconciliation-live-source"));
        fixture.addVersion(version("pkg-skill-a", "skill-a", "1.0.0", "published", NOW.minusSeconds(120)));
        RecordingProjectionRepository repository = new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 0L, ZERO_HASH, 0, 0, 0, 0, 0));
        SkillLifecycleProjectionService service = fixture.service(repository);
        String sourceHash = service.preflight().sourceSha256();
        repository.setStatus(SkillLifecycleProjectionStatus.ready(
                "json", null, 0L, sourceHash, 1, 1, 0, 0, 0));

        SkillLifecycleProjectionReconciliation result = service.reconciliation();

        assertThat(result.state()).isEqualTo("LIVE_SOURCE");
        assertThat(result.importedAt()).isNull();
        assertThat(result.projectionAgeSeconds()).isNull();
    }

    @Test
    void importRejectsStaleExpectedHashBeforeDatabaseMutation() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("stale-hash"));
        fixture.addVersion(version("pkg-skill-a", "skill-a", "1.0.0", "published", NOW.minusSeconds(120)));
        fixture.addRelease(release("release-a", "skill-a", "1.0.0", ReleaseEnvironment.STAGING,
                ReleaseStatus.REQUESTED, "a".repeat(64)));
        RecordingProjectionRepository repository = new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 11L, ZERO_HASH, 0, 0, 0, 0, 0));
        SkillLifecycleProjectionService service = fixture.service(repository);

        SkillLifecycleProjectionImportResult result = service.importSnapshot(ZERO_HASH, admin(), "req-stale");

        assertThat(result).isEqualTo(new SkillLifecycleProjectionImportResult(
                false, false, 11L, service.preflight().sourceSha256(), 1, 1, 1, 0, 0,
                "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED"));
        assertThat(repository.replacements()).isEmpty();
    }

    @Test
    void identicalSourceHashIsIdempotent() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("idempotent"));
        fixture.addVersion(version("pkg-skill-a", "skill-a", "1.0.0", "published", NOW.minusSeconds(120)));
        fixture.addScope(scope("skill-a", SkillVisibility.PUBLIC, "team-public", List.of("maintainer-a"), 2));
        RecordingProjectionRepository repository = new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 4L, ZERO_HASH, 0, 0, 0, 0, 0));
        SkillLifecycleProjectionService service = fixture.service(repository);
        String expectedHash = service.preflight().sourceSha256();
        repository.setStatus(SkillLifecycleProjectionStatus.ready("json", null, 4L, expectedHash, 1, 1, 0, 1, 0));

        SkillLifecycleProjectionImportResult result = service.importSnapshot(expectedHash, admin(), "req-idempotent");

        assertThat(result).isEqualTo(new SkillLifecycleProjectionImportResult(
                false, true, 4L, expectedHash, 1, 1, 0, 1, 0, ""));
        assertThat(repository.replacements()).hasSize(1);
        assertThat(repository.status().revision()).isEqualTo(4L);
    }

    @Test
    void findSkillsUsesRepositoryQueriesInsteadOfLiveSourceReads() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("query-routing-skills"));
        fixture.addVersion(version("pkg-skill-a", "skill-a", "1.0.0", "published", NOW.minusSeconds(120)));
        fixture.addScope(scope("skill-a", SkillVisibility.PUBLIC, "team-public", List.of("maintainer-a"), 1));
        RecordingProjectionRepository repository = new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 3L, ZERO_HASH, 1, 1, 0, 1, 0));
        repository.setSkillResults(List.of(new SkillLifecycleProjectionView(
                "skill-a", "1.0.0", "pkg-skill-a", "PUBLISHED",
                "1.0.0", "PUBLISHED", 1, 1, 0, "PUBLIC", "team-public", List.of())));
        SkillLifecycleProjectionService service = fixture.service(fixture.failingSource("findSkills must query the repository"), repository);

        List<SkillLifecycleProjectionView> views = service.findSkills(SkillLifecycleProjectionQuery.defaults(), actor("viewer-1", "viewer"));

        assertThat(views).extracting(SkillLifecycleProjectionView::skillId).containsExactly("skill-a");
        assertThat(repository.skillQueries()).containsExactly(SkillLifecycleProjectionQuery.defaults());
        assertThat(repository.impactRequests()).isEmpty();
    }

    @Test
    void findImpactUsesRepositoryQueriesAndAppliesStableVisibilityAndTruncationBoundaries() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("query-routing-impact"));
        fixture.addVersion(version("pkg-root", "root-skill", "1.0.0", "published", NOW.minusSeconds(200)));
        fixture.addScope(scope("root-skill", SkillVisibility.PUBLIC, "team-root", List.of("maintainer-root"), 1));
        for (int index = MAX_IMPACT_NODES + 1; index >= 1; index--) {
            String skillId = "visible-skill-%03d".formatted(index);
            fixture.addVersion(version("pkg-" + skillId, skillId, "1.0.0", "published", NOW.minusSeconds(200 - index)));
            fixture.addScope(scope(skillId, SkillVisibility.PUBLIC, "team-visible", List.of("maintainer-" + index), 1));
        }
        fixture.addVersion(version("pkg-hidden", "hidden-skill", "1.0.0", "published", NOW.minusSeconds(50)));
        fixture.addScope(scope("hidden-skill", SkillVisibility.RESTRICTED, "", List.of("named-maintainer"), 1));
        RecordingProjectionRepository repository = new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 5L, ZERO_HASH, 0, 0, 0, 0, 0));
        List<SkillLifecycleImpactView.Node> nodes = new ArrayList<>();
        nodes.add(new SkillLifecycleImpactView.Node(
                "relation-hidden", "hidden-skill", "1.0.0", "DEPENDS_ON", "ACTIVE", 1, false, List.of()));
        for (int index = MAX_IMPACT_NODES + 1; index >= 1; index--) {
            String skillId = "visible-skill-%03d".formatted(index);
            nodes.add(new SkillLifecycleImpactView.Node(
                    "relation-%03d".formatted(index),
                    skillId,
                    "1.0.0",
                    "DEPENDS_ON",
                    "ACTIVE",
                    1,
                    index % 2 == 0,
                    List.of(new SkillLifecycleProjectionView.ReleaseView(
                            "release-%03d".formatted(index),
                            ReleaseEnvironment.PRODUCTION.name(),
                            ReleaseStatus.PROMOTED.name(),
                            "PASSED"))));
        }
        repository.setImpactResult(new SkillLifecycleImpactView("root-skill", "1.0.0", false, nodes));
        SkillLifecycleProjectionService service = fixture.service(
                fixture.failingSource("findImpact must query the repository"),
                repository);

        SkillLifecycleImpactView impact = service.findImpact("root-skill", "1.0.0", actor("viewer-1", "viewer"));

        assertThat(impact.rootSkillId()).isEqualTo("root-skill");
        assertThat(impact.rootVersion()).isEqualTo("1.0.0");
        assertThat(impact.truncated()).isTrue();
        assertThat(impact.nodes()).hasSize(MAX_IMPACT_NODES);
        assertThat(impact.nodes())
                .extracting(SkillLifecycleImpactView.Node::skillId)
                .doesNotContain("hidden-skill")
                .startsWith("visible-skill-001", "visible-skill-002", "visible-skill-003")
                .endsWith("visible-skill-100");
        assertThat(repository.skillQueries()).isEmpty();
        assertThat(repository.impactRequests()).containsExactly("root-skill\u00001.0.0");
    }

    @Test
    void malformedRelationTargetFailsWithStableSourceInvalidCode() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("bad-relation"));
        fixture.addVersion(version("pkg-visible", "visible-skill", "1.0.0", "published", NOW.minusSeconds(120)));
        fixture.addRelation(relation("relation-bad", "visible-skill", "1.0.0",
                "hidden-trace-target", "9.9.9", SkillRelationStatus.ACTIVE));
        SkillLifecycleProjectionService service = fixture.service(new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 0L, ZERO_HASH, 0, 0, 0, 0, 0)));

        assertThatThrownBy(service::preflight)
                .isInstanceOf(SkillLifecycleProjectionSourceInvalidException.class)
                .hasMessage("Skill lifecycle projection source is invalid")
                .extracting("code")
                .isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID");
    }

    @Test
    void publishedVersionWithoutPublicationMetadataFailsClosed() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("bad-published-metadata"));
        fixture.addVersion(version("pkg-published", "published-skill", "1.0.0", "published",
                NOW.minusSeconds(120), "", null, null, null, null, null));
        SkillLifecycleProjectionService service = fixture.service(new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 0L, ZERO_HASH, 0, 0, 0, 0, 0)));

        assertThatThrownBy(service::preflight)
                .isInstanceOf(SkillLifecycleProjectionSourceInvalidException.class)
                .hasMessage("Skill lifecycle projection source is invalid")
                .extracting("code")
                .isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID");
    }

    @Test
    void deprecatedVersionWithoutLifecycleChangeMetadataFailsClosed() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("bad-deprecated-metadata"));
        fixture.addVersion(version("pkg-deprecated", "deprecated-skill", "1.0.0", "deprecated",
                NOW.minusSeconds(120), "reviewer", NOW.minusSeconds(60), null, "1.1.0", "", null));
        SkillLifecycleProjectionService service = fixture.service(new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 0L, ZERO_HASH, 0, 0, 0, 0, 0)));

        assertThatThrownBy(service::preflight)
                .isInstanceOf(SkillLifecycleProjectionSourceInvalidException.class)
                .hasMessage("Skill lifecycle projection source is invalid")
                .extracting("code")
                .isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID");
    }

    @Test
    void withdrawnVersionWithoutLifecycleChangeMetadataFailsClosed() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("bad-withdrawn-metadata"));
        fixture.addVersion(version("pkg-withdrawn", "withdrawn-skill", "1.0.0", "withdrawn",
                NOW.minusSeconds(120), "reviewer", NOW.minusSeconds(60), "security incident", null, "", null));
        SkillLifecycleProjectionService service = fixture.service(new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 0L, ZERO_HASH, 0, 0, 0, 0, 0)));

        assertThatThrownBy(service::preflight)
                .isInstanceOf(SkillLifecycleProjectionSourceInvalidException.class)
                .hasMessage("Skill lifecycle projection source is invalid")
                .extracting("code")
                .isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID");
    }

    @Test
    void replacementVersionCannotReferenceTheSameVersion() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("replacement-self"));
        fixture.addVersion(version("pkg-self", "replacement-skill", "1.0.0", "deprecated",
                NOW.minusSeconds(120), "reviewer", NOW.minusSeconds(90),
                "migrate", "1.0.0", "admin", NOW.minusSeconds(60)));
        SkillLifecycleProjectionService service = fixture.service(new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 0L, ZERO_HASH, 0, 0, 0, 0, 0)));

        assertThatThrownBy(service::preflight)
                .isInstanceOf(SkillLifecycleProjectionSourceInvalidException.class)
                .hasMessage("Skill lifecycle projection source is invalid")
                .extracting("code")
                .isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID");
    }

    @Test
    void replacementVersionMustExistOnTheSameSkill() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("replacement-dangling"));
        fixture.addVersion(version("pkg-dangling", "replacement-skill", "1.0.0", "deprecated",
                NOW.minusSeconds(120), "reviewer", NOW.minusSeconds(90),
                "migrate", "9.9.9", "admin", NOW.minusSeconds(60)));
        SkillLifecycleProjectionService service = fixture.service(new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 0L, ZERO_HASH, 0, 0, 0, 0, 0)));

        assertThatThrownBy(service::preflight)
                .isInstanceOf(SkillLifecycleProjectionSourceInvalidException.class)
                .hasMessage("Skill lifecycle projection source is invalid")
                .extracting("code")
                .isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID");
    }

    @Test
    void replacementVersionMustPointToPublishedOrDeprecatedTarget() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("replacement-invalid-status"));
        fixture.addVersion(version("pkg-source", "replacement-skill", "1.0.0", "deprecated",
                NOW.minusSeconds(120), "reviewer", NOW.minusSeconds(90),
                "migrate", "2.0.0", "admin", NOW.minusSeconds(60)));
        fixture.addVersion(version("pkg-target", "replacement-skill", "2.0.0", "active", NOW.minusSeconds(100)));
        SkillLifecycleProjectionService service = fixture.service(new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 0L, ZERO_HASH, 0, 0, 0, 0, 0)));

        assertThatThrownBy(service::preflight)
                .isInstanceOf(SkillLifecycleProjectionSourceInvalidException.class)
                .hasMessage("Skill lifecycle projection source is invalid")
                .extracting("code")
                .isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID");
    }

    @Test
    void hiddenSkillImpactIsNotReturnedToUnauthorizedActor() {
        LifecycleFixture fixture = new LifecycleFixture(tempDir.resolve("hidden-impact"));
        fixture.addVersion(version("pkg-hidden", "hidden-skill", "1.0.0", "published", NOW.minusSeconds(120)));
        fixture.addScope(scope("hidden-skill", SkillVisibility.RESTRICTED, "", List.of("named-maintainer"), 1));
        SkillLifecycleProjectionService service = fixture.service(new RecordingProjectionRepository(
                SkillLifecycleProjectionStatus.ready("json", null, 0L, ZERO_HASH, 0, 0, 0, 0, 0)));

        assertThatThrownBy(() -> service.findImpact("hidden-skill", "1.0.0", actor("outsider", "viewer")))
                .isInstanceOf(SkillNotVisibleException.class);
    }

    @Test
    void jsonContextHasNoDataSourceFlywayOrPostgresqlStoreAndStillExposesRepositoryAndService() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(
                        PersistenceControlProperties.class,
                        SkillLifecycleProjectionConfiguration.class,
                        JsonLifecycleProjectionContextConfiguration.class)
                .withPropertyValues(
                        "skill-center.lifecycle-projection.backend=json",
                        "skill-center.governance-storage=" + tempDir.resolve("context/governance.json"),
                        "skill-center.release-storage=" + tempDir.resolve("context/releases.json"),
                        "skill-center.skill-scope-storage=" + tempDir.resolve("context/scopes.json"),
                        "skill-center.skill-relations-storage=" + tempDir.resolve("context/relations.json"));

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
            assertThat(context.getBeansOfType(DataSourceTransactionManager.class)).isEmpty();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(context.getBeansOfType(PostgresSkillLifecycleProjectionStore.class)).isEmpty();
            assertThat(context).hasSingleBean(SkillLifecycleProjectionRepository.class);
            assertThat(context).hasSingleBean(JsonSkillLifecycleProjectionStore.class);
            assertThat(context).hasSingleBean(SkillLifecycleProjectionService.class);
        });
    }

    private static Actor admin() {
        return actor("admin-1", "admin");
    }

    private static Actor actor(String userId, String role) {
        return new Actor(userId, role);
    }

    private static SkillVersion version(String packageId, String skillId, String version, String status, Instant uploadedAt) {
        return version(packageId, skillId, version, status, uploadedAt,
                "reviewer", uploadedAt.plusSeconds(5), null, null, null, null);
    }

    private static SkillVersion version(String packageId, String skillId, String version, String status, Instant uploadedAt,
                                        String publishedBy, Instant publishedAt, String statusReason,
                                        String replacementVersion, String statusChangedBy, Instant statusChangedAt) {
        return new SkillVersion(packageId, skillId, version, status, "a".repeat(64), 64L, "",
                "owner", uploadedAt, publishedBy, publishedAt, "review-" + packageId,
                statusReason, replacementVersion, statusChangedBy, statusChangedAt, "low");
    }

    private static ReleaseRecord release(String releaseId, String skillId, String version,
                                         ReleaseEnvironment environment, ReleaseStatus status, String sha256) {
        ReleaseRecord requested = ReleaseRecord.request(releaseId, skillId, version, sha256, environment,
                ReleaseGateSnapshot.passed(NOW), "idem-" + releaseId, "owner", NOW.minusSeconds(30));
        return switch (status) {
            case REQUESTED -> requested;
            case APPROVED -> requested.approve("reviewer", NOW.minusSeconds(20));
            case PROMOTING -> requested.approve("reviewer", NOW.minusSeconds(20))
                    .promoting("mock/" + releaseId, NOW.minusSeconds(10));
            case PROMOTED -> requested.approve("reviewer", NOW.minusSeconds(20))
                    .promoting("mock/" + releaseId, NOW.minusSeconds(10))
                    .promoted("mock/" + releaseId, NOW.minusSeconds(5));
            default -> throw new IllegalArgumentException("unsupported test status " + status);
        };
    }

    private static SkillScope scope(String skillId, SkillVisibility visibility, String ownerTeamId,
                                    List<String> maintainers, int revision) {
        return new SkillScope(skillId, visibility, ownerTeamId, maintainers, revision,
                "scope-admin", NOW.minusSeconds(60), "scope-admin", NOW.minusSeconds(30));
    }

    private static SkillRelation relation(String relationId, String sourceSkillId, String sourceVersion,
                                          String targetSkillId, String targetVersion, SkillRelationStatus status) {
        SkillRelation active = SkillRelation.create(relationId, sourceSkillId, sourceVersion,
                targetSkillId, targetVersion, SkillRelationType.DEPENDS_ON, "relation-admin", NOW.minusSeconds(15));
        return status == SkillRelationStatus.ACTIVE
                ? active
                : active.retire("relation-admin", "retired", NOW.minusSeconds(10));
    }

    private static final class RecordingProjectionRepository implements SkillLifecycleProjectionRepository {
        private SkillLifecycleProjectionStatus status;
        private java.util.Optional<Instant> importedAt = java.util.Optional.empty();
        private final java.util.ArrayList<SkillLifecycleProjectionSnapshot> replacements = new java.util.ArrayList<>();
        private List<SkillLifecycleProjectionView> skillResults = List.of();
        private SkillLifecycleImpactView impactResult = new SkillLifecycleImpactView("", "", false, List.of());
        private final java.util.ArrayList<SkillLifecycleProjectionQuery> skillQueries = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> impactRequests = new java.util.ArrayList<>();

        private RecordingProjectionRepository(SkillLifecycleProjectionStatus status) {
            this.status = status;
        }

        @Override
        public SkillLifecycleProjectionStatus status() {
            return status;
        }

        @Override
        public java.util.Optional<Instant> importedAt() {
            return importedAt;
        }

        @Override
        public SkillLifecycleProjectionImportResult replace(SkillLifecycleProjectionSnapshot snapshot) {
            replacements.add(snapshot);
            if (status.sourceSha256().equals(snapshot.sourceSha256())) {
                return new SkillLifecycleProjectionImportResult(
                        false, true, status.revision(), snapshot.sourceSha256(),
                        snapshot.skills().size(), snapshot.versions().size(), snapshot.releases().size(),
                        snapshot.scopes().size(), snapshot.relations().size(), "");
            }
            long nextRevision = status.revision() + 1L;
            status = SkillLifecycleProjectionStatus.ready(
                    status.backend(), status.schemaVersion(), nextRevision, snapshot.sourceSha256(),
                    snapshot.skills().size(), snapshot.versions().size(), snapshot.releases().size(),
                    snapshot.scopes().size(), snapshot.relations().size());
            return new SkillLifecycleProjectionImportResult(
                    true, false, nextRevision, snapshot.sourceSha256(), snapshot.skills().size(),
                    snapshot.versions().size(), snapshot.releases().size(), snapshot.scopes().size(),
                    snapshot.relations().size(), "");
        }

        @Override
        public List<SkillLifecycleProjectionView> findSkills(SkillLifecycleProjectionQuery query) {
            skillQueries.add(query);
            return skillResults;
        }

        @Override
        public SkillLifecycleImpactView findImpact(String skillId, String version) {
            impactRequests.add(skillId + "\u0000" + version);
            return impactResult;
        }

        private void setStatus(SkillLifecycleProjectionStatus status) {
            this.status = status;
        }

        private void setImportedAt(Instant importedAt) {
            this.importedAt = java.util.Optional.ofNullable(importedAt);
        }

        private void setSkillResults(List<SkillLifecycleProjectionView> skillResults) {
            this.skillResults = List.copyOf(skillResults);
        }

        private void setImpactResult(SkillLifecycleImpactView impactResult) {
            this.impactResult = impactResult;
        }

        private List<SkillLifecycleProjectionSnapshot> replacements() {
            return List.copyOf(replacements);
        }

        private List<SkillLifecycleProjectionQuery> skillQueries() {
            return List.copyOf(skillQueries);
        }

        private List<String> impactRequests() {
            return List.copyOf(impactRequests);
        }
    }

    private static final class LifecycleFixture {
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        private final GovernanceStore governanceStore;
        private final ReleaseRecordStore releaseStore;
        private final SkillScopeStore scopeStore;
        private final SkillRelationStore relationStore;
        private final SkillAuthorizationService authorizationService;

        private LifecycleFixture(Path root) {
            governanceStore = new GovernanceStore(root.resolve("governance.json"), List.of());
            releaseStore = new ReleaseRecordStore(mapper, root.resolve("releases.json").toString());
            scopeStore = new SkillScopeStore(mapper, root.resolve("scopes.json").toString());
            relationStore = new SkillRelationStore(mapper, root.resolve("relations.json").toString());
            authorizationService = new SkillAuthorizationService(scopeStore, governanceStore);
        }

        private void addVersion(SkillVersion version) {
            governanceStore.createPendingVersion(version,
                    new ReviewTask("review-" + version.packageId(), version.packageId(), version.skillId(),
                            version.version(), "approved", version.uploadedBy(), version.uploadedAt(),
                            "reviewer", version.uploadedAt(), null),
                    new AuditEvent("audit-" + version.packageId(), "PACKAGE_IMPORTED", "SKILL_VERSION",
                            version.packageId(), "system", "admin", "seed", NOW,
                            Map.of("skillId", version.skillId(), "version", version.version())));
        }

        private void addRelease(ReleaseRecord record) {
            releaseStore.create(record);
        }

        private void addScope(SkillScope scope) {
            scopeStore.create(scope);
        }

        private void addRelation(SkillRelation relation) {
            relationStore.create(relation);
        }

        private SkillLifecycleProjectionService service(SkillLifecycleProjectionRepository repository) {
            SkillLifecycleProjectionSource source = new SkillLifecycleProjectionSource(
                    governanceStore, releaseStore, scopeStore, relationStore, clock);
            return new SkillLifecycleProjectionService(source, repository, authorizationService, clock);
        }

        private SkillLifecycleProjectionService service(SkillLifecycleProjectionSource source,
                                                        SkillLifecycleProjectionRepository repository) {
            return new SkillLifecycleProjectionService(source, repository, authorizationService, clock);
        }

        private SkillLifecycleProjectionSource failingSource(String message) {
            return new SkillLifecycleProjectionSource(governanceStore, releaseStore, scopeStore, relationStore, clock) {
                @Override
                public SkillLifecycleProjectionInput read() {
                    throw new AssertionError(message);
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class JsonLifecycleProjectionContextConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        GovernanceStore governanceStore(
                @Value("${skill-center.governance-storage}") String governanceStorage) {
            return new GovernanceStore(Path.of(governanceStorage), List.of());
        }

        @Bean
        ReleaseRecordStore releaseRecordStore(ObjectMapper objectMapper,
                                             @Value("${skill-center.release-storage}") String releaseStorage) {
            return new ReleaseRecordStore(objectMapper, releaseStorage);
        }

        @Bean
        SkillScopeStore skillScopeStore(ObjectMapper objectMapper,
                                        @Value("${skill-center.skill-scope-storage}") String scopeStorage) {
            return new SkillScopeStore(objectMapper, scopeStorage);
        }

        @Bean
        SkillRelationStore skillRelationStore(ObjectMapper objectMapper,
                                             @Value("${skill-center.skill-relations-storage}") String relationStorage) {
            return new SkillRelationStore(objectMapper, relationStorage);
        }

        @Bean
        SkillAuthorizationService skillAuthorizationService(SkillScopeStore scopeStore, GovernanceStore governanceStore) {
            return new SkillAuthorizationService(scopeStore, governanceStore);
        }
    }
}
