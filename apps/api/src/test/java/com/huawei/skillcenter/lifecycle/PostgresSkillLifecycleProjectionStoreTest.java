package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.access.SkillVisibility;
import com.huawei.skillcenter.release.ReleaseEnvironment;
import com.huawei.skillcenter.release.ReleaseStatus;
import com.huawei.skillcenter.relationship.SkillRelationStatus;
import com.huawei.skillcenter.relationship.SkillRelationType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSkillLifecycleProjectionStoreTest {
    private static final Instant AT = Instant.parse("2026-08-25T00:00:00Z");
    private static final String ZERO_HASH = "0".repeat(64);

    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbcTemplate;
    private boolean dockerAvailable;

    @BeforeAll
    void startPostgres() {
        dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        if (!dockerAvailable) return;
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        jdbcTemplate = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void resetProjectionTables() {
        if (!dockerAvailable) return;
        jdbcTemplate.update("delete from skill_lifecycle_relation_projection");
        jdbcTemplate.update("delete from skill_lifecycle_release_projection");
        jdbcTemplate.update("delete from skill_lifecycle_version_security_finding_projection");
        jdbcTemplate.update("delete from skill_lifecycle_scope_projection");
        jdbcTemplate.update("delete from skill_lifecycle_version_projection");
        jdbcTemplate.update("delete from skill_lifecycle_skill_projection");
        jdbcTemplate.update("""
                update skill_lifecycle_projection_meta
                   set schema_version = 3,
                       revision = 0,
                       source_sha256 = ?,
                       source_generated_at = current_timestamp,
                       imported_at = current_timestamp,
                       skill_count = 0,
                       version_count = 0,
                       release_count = 0,
                       scope_count = 0,
                       relation_count = 0
                 where projection_key = 'skill-lifecycle'
                """, ZERO_HASH);
    }

    @Test
    void statusToStringRedactsHashesAndSecrets() {
        SkillLifecycleProjectionStatus status = new SkillLifecycleProjectionStatus(
                "postgresql", "FAIL_CLOSED", "jdbc:postgresql://db?password=secret", "2",
                7L, "a".repeat(64), 1, 2, 3, 4, 5);

        assertThat(status.reasonCode()).isEqualTo("SKILL_LIFECYCLE_PROJECTION_NOT_READY");
        assertThat(status.toString()).doesNotContain("jdbc:", "password", "secret", "a".repeat(64));
    }

    @Test
    void statusReturnsTheSeededEmptyProjectionBeforeFirstImport() {
        requireDocker();

        assertThat(store().status()).isEqualTo(new SkillLifecycleProjectionStatus(
                "postgresql", "EMPTY", "", "3", 0L, ZERO_HASH, 0, 0, 0, 0, 0));
    }

    @Test
    void replaceImportsProjectionRowsAndAdvancesRevision() {
        requireDocker();

        SkillLifecycleProjectionSnapshot snapshot = snapshot("a".repeat(64), "1.0.0");

        assertThat(store().replace(snapshot)).isEqualTo(new SkillLifecycleProjectionImportResult(
                true, false, 1L, snapshot.sourceSha256(), 2, 2, 1, 2, 1, ""));
        assertThat(store().status()).isEqualTo(new SkillLifecycleProjectionStatus(
                "postgresql", "READY", "", "3", 1L, snapshot.sourceSha256(), 2, 2, 1, 2, 1));
        assertThat(count("skill_lifecycle_skill_projection")).isEqualTo(2L);
        assertThat(count("skill_lifecycle_version_projection")).isEqualTo(2L);
        assertThat(count("skill_lifecycle_release_projection")).isEqualTo(1L);
        assertThat(count("skill_lifecycle_scope_projection")).isEqualTo(2L);
        assertThat(count("skill_lifecycle_relation_projection")).isEqualTo(1L);
    }

    @Test
    void replacePersistsAndReplacesMetadataOnlySecurityFindings() {
        requireDocker();
        SkillLifecycleProjectionRepository repository = store();

        repository.replace(securitySnapshot("a".repeat(64), List.of(
                new SkillLifecycleSecurityFindingRow("SECRET_PATTERN", "skill/SKILL.md", "HIGH"))));

        assertThat(jdbcTemplate.queryForObject("""
                select security_status || '|' || security_scanner_id || '|' || security_scanner_version
                  from skill_lifecycle_version_projection
                 where skill_id = 'skill-a' and version = '1.0.0'
                """, String.class)).isEqualTo("BLOCKED|local-package-security|1");
        assertThat(count("skill_lifecycle_version_security_finding_projection")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                select finding_code || '|' || finding_path || '|' || finding_severity
                  from skill_lifecycle_version_security_finding_projection
                """, String.class)).isEqualTo("SECRET_PATTERN|skill/SKILL.md|HIGH");

        repository.replace(securitySnapshot("b".repeat(64), List.of()));

        assertThat(count("skill_lifecycle_version_security_finding_projection")).isZero();
    }

    @Test
    void replaceTreatsTheSameSourceHashAsIdempotentWithoutChangingRevision() {
        requireDocker();
        SkillLifecycleProjectionRepository repository = store();
        SkillLifecycleProjectionSnapshot snapshot = snapshot("b".repeat(64), "1.0.0");

        repository.replace(snapshot);

        assertThat(repository.replace(snapshot)).isEqualTo(new SkillLifecycleProjectionImportResult(
                false, true, 1L, snapshot.sourceSha256(), 2, 2, 1, 2, 1, ""));
        assertThat(repository.status().revision()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                select source_sha256 from skill_lifecycle_projection_meta
                 where projection_key = 'skill-lifecycle'
                """, String.class)).isEqualTo(snapshot.sourceSha256());
    }

    @Test
    void importedAtExposesOnlyTheProjectionMetadataTimestamp() {
        requireDocker();

        Instant before = Instant.now();
        Instant importedAt = store().importedAt().orElseThrow();

        assertThat(importedAt).isAfterOrEqualTo(before.minusSeconds(5));
        assertThat(importedAt).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
    }

    @Test
    void replaceUsesParameterBindingForQuotedVersionValues() {
        requireDocker();
        SkillLifecycleProjectionSnapshot snapshot = snapshot("c".repeat(64), "1.0.0''beta");

        SkillLifecycleProjectionImportResult result = store().replace(snapshot);

        assertThat(result.imported()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select version from skill_lifecycle_version_projection
                 where skill_id = ? and version = ?
                """, String.class, "skill-a", "1.0.0''beta")).isEqualTo("1.0.0''beta");
    }

    @Test
    void findSkillsReturnsImportedProjectionRowsFromProjectionTables() {
        requireDocker();
        SkillLifecycleProjectionRepository repository = store();
        repository.replace(snapshot("c".repeat(64), "1.0.0"));

        List<SkillLifecycleProjectionView> views = repository.findSkills(
                new SkillLifecycleProjectionQuery("skill-a", "", "", null, 1, 50));

        assertThat(views).containsExactly(new SkillLifecycleProjectionView(
                "skill-a",
                "1.0.0",
                "package-a",
                "PUBLISHED",
                "1.0.0",
                "PUBLISHED",
                1,
                1,
                1,
                "PUBLIC",
                "team-a",
                List.of(new SkillLifecycleProjectionView.ReleaseView(
                        "release-a",
                        ReleaseEnvironment.PRODUCTION.name(),
                        ReleaseStatus.PROMOTED.name(),
                        "PASSED"))));
    }

    @Test
    void findImpactReturnsImportedProjectionRowsFromProjectionTables() {
        requireDocker();
        SkillLifecycleProjectionRepository repository = store();
        repository.replace(snapshot("d".repeat(64), "1.0.0"));

        SkillLifecycleImpactView impact = repository.findImpact("skill-b", "2.0.0");

        assertThat(impact).isEqualTo(new SkillLifecycleImpactView(
                "skill-b",
                "2.0.0",
                false,
                List.of(new SkillLifecycleImpactView.Node(
                        "relation-a",
                        "skill-a",
                        "1.0.0",
                        SkillRelationType.DEPENDS_ON.name(),
                        SkillRelationStatus.ACTIVE.name(),
                        1,
                        true,
                        List.of(new SkillLifecycleProjectionView.ReleaseView(
                                "release-a",
                                ReleaseEnvironment.PRODUCTION.name(),
                                ReleaseStatus.PROMOTED.name(),
                                "PASSED"))))));
    }

    @Test
    void findImpactTruncatesAtRepositoryBoundariesAndQueriesOnlyBoundedReleaseRows() {
        requireDocker();
        SkillLifecycleProjectionRepository repository = store(new GuardedJdbcTemplate(jdbcTemplate.getDataSource()));
        repository.replace(wideImpactSnapshot());

        SkillLifecycleImpactView impact = repository.findImpact("root-skill", "1.0.0");

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
    void findImpactMarksDepthTruncationInPostgresqlProjection() {
        requireDocker();
        SkillLifecycleProjectionRepository repository = store(new GuardedJdbcTemplate(jdbcTemplate.getDataSource()));
        repository.replace(deepImpactSnapshot());

        SkillLifecycleImpactView impact = repository.findImpact("root-skill", "1.0.0");

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

    @Test
    void repositoryDoesNotUseDynamicTableNamesForProjectionCountVerification() {
        assertThat(Arrays.stream(PostgresSkillLifecycleProjectionStore.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("verifyCount")
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[0] == String.class))
                .isFalse();
    }

    @Test
    void replaceAdvancesRevisionWhenTheSourceHashChanges() {
        requireDocker();
        SkillLifecycleProjectionRepository repository = store();

        repository.replace(snapshot("d".repeat(64), "1.0.0"));
        SkillLifecycleProjectionImportResult result = repository.replace(snapshot("e".repeat(64), "1.0.1"));

        assertThat(result).isEqualTo(new SkillLifecycleProjectionImportResult(
                true, false, 2L, "e".repeat(64), 2, 2, 1, 2, 1, ""));
        assertThat(repository.status().revision()).isEqualTo(2L);
    }

    @Test
    void replaceRejectsRevisionOverflowWithoutChangingTheStoredProjection() {
        requireDocker();
        SkillLifecycleProjectionSnapshot original = snapshot("f".repeat(64), "1.0.0");
        SkillLifecycleProjectionRepository repository = store();
        repository.replace(original);
        jdbcTemplate.update("""
                update skill_lifecycle_projection_meta
                   set revision = ?
                 where projection_key = 'skill-lifecycle'
                """, Long.MAX_VALUE);

        SkillLifecycleProjectionImportResult failed = repository.replace(snapshot("9".repeat(64), "2.0.0"));

        assertThat(failed).isEqualTo(new SkillLifecycleProjectionImportResult(
                false, false, Long.MAX_VALUE, "9".repeat(64), 2, 2, 1, 2, 1,
                "SKILL_LIFECYCLE_PROJECTION_IMPORT_FAILED"));
        assertThat(repository.status()).isEqualTo(new SkillLifecycleProjectionStatus(
                "postgresql", "READY", "", "3", Long.MAX_VALUE, original.sourceSha256(), 2, 2, 1, 2, 1));
    }

    @Test
    void replaceRollsBackAllTablesWhenForeignKeysAreViolated() {
        requireDocker();
        SkillLifecycleProjectionRepository repository = store();
        SkillLifecycleProjectionSnapshot original = snapshot("1".repeat(64), "1.0.0");
        repository.replace(original);
        SkillLifecycleProjectionSnapshot invalid = new SkillLifecycleProjectionSnapshot(
                "2".repeat(64), AT, List.of(skill("skill-a", "2.0.0")),
                List.of(version("skill-a", "2.0.0")),
                List.of(release("release-bad", "skill-a", "9.9.9")),
                List.of(scope("skill-a")),
                List.of());

        SkillLifecycleProjectionImportResult failed = repository.replace(invalid);

        assertThat(failed.imported()).isFalse();
        assertThat(failed.idempotent()).isFalse();
        assertThat(failed.reasonCode()).isEqualTo("SKILL_LIFECYCLE_PROJECTION_IMPORT_FAILED");
        assertThat(repository.status()).isEqualTo(new SkillLifecycleProjectionStatus(
                "postgresql", "READY", "", "3", 1L, original.sourceSha256(), 2, 2, 1, 2, 1));
        assertThat(jdbcTemplate.queryForObject("""
                select version from skill_lifecycle_version_projection
                 where skill_id = ? and version = ?
                """, String.class, "skill-a", "1.0.0")).isEqualTo("1.0.0");
        assertThat(count("skill_lifecycle_release_projection")).isEqualTo(1L);
    }

    private PostgresSkillLifecycleProjectionStore store() {
        return store(jdbcTemplate);
    }

    private PostgresSkillLifecycleProjectionStore store(JdbcTemplate template) {
        return new PostgresSkillLifecycleProjectionStore(
                template,
                new DataSourceTransactionManager(template.getDataSource()));
    }

    private void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable,
                "CAPABILITY_SKIP: Docker is unavailable; PostgreSQL Testcontainers integration cannot run");
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private SkillLifecycleProjectionSnapshot snapshot(String sourceSha256, String version) {
        return new SkillLifecycleProjectionSnapshot(
                sourceSha256,
                AT,
                List.of(skill("skill-a", version), skill("skill-b", "2.0.0")),
                List.of(version("skill-a", version), version("skill-b", "2.0.0")),
                List.of(release("release-a", "skill-a", version)),
                List.of(scope("skill-a"), scope("skill-b")),
                List.of(relation("relation-a", version)));
    }

    private SkillLifecycleProjectionSnapshot securitySnapshot(String sourceSha256,
                                                               List<SkillLifecycleSecurityFindingRow> findings) {
        return new SkillLifecycleProjectionSnapshot(
                sourceSha256,
                AT,
                List.of(skill("skill-a", "1.0.0")),
                List.of(new SkillLifecycleVersionRow("skill-a", "1.0.0", "package-a", "PUBLISHED",
                        "a".repeat(64), 12L, "uploader", AT, AT, "LOW", "BLOCKED",
                        "local-package-security", "1", findings)),
                List.of(),
                List.of(scope("skill-a")),
                List.of());
    }

    private SkillLifecycleProjectionSnapshot wideImpactSnapshot() {
        List<SkillLifecycleSkillRow> skills = new ArrayList<>();
        List<SkillLifecycleVersionRow> versions = new ArrayList<>();
        List<SkillLifecycleReleaseRow> releases = new ArrayList<>();
        List<SkillLifecycleScopeRow> scopes = new ArrayList<>();
        List<SkillLifecycleRelationRow> relations = new ArrayList<>();
        skills.add(skill("root-skill", "1.0.0"));
        versions.add(version("root-skill", "1.0.0", "package-root", "a".repeat(64)));
        scopes.add(scope("root-skill", "team-root"));
        for (int index = 1; index <= 101; index++) {
            String skillId = "downstream-%03d".formatted(index);
            String version = "1.0.0";
            String hash = hashFor(index);
            skills.add(skill(skillId, version));
            versions.add(version(skillId, version, "package-" + skillId, hash));
            releases.add(release("release-%03d".formatted(index), skillId, version, hash));
            scopes.add(scope(skillId, "team-wide"));
            relations.add(new SkillLifecycleRelationRow(
                    "relation-%03d".formatted(index),
                    skillId,
                    version,
                    "root-skill",
                    "1.0.0",
                    SkillRelationType.DEPENDS_ON,
                    SkillRelationStatus.ACTIVE,
                    AT,
                    null));
        }
        return new SkillLifecycleProjectionSnapshot(
                "1".repeat(64),
                AT,
                List.copyOf(skills),
                List.copyOf(versions),
                List.copyOf(releases),
                List.copyOf(scopes),
                List.copyOf(relations));
    }

    private SkillLifecycleProjectionSnapshot deepImpactSnapshot() {
        List<SkillLifecycleSkillRow> skills = new ArrayList<>();
        List<SkillLifecycleVersionRow> versions = new ArrayList<>();
        List<SkillLifecycleReleaseRow> releases = new ArrayList<>();
        List<SkillLifecycleScopeRow> scopes = new ArrayList<>();
        List<SkillLifecycleRelationRow> relations = new ArrayList<>();
        skills.add(skill("root-skill", "1.0.0"));
        versions.add(version("root-skill", "1.0.0", "package-root", "a".repeat(64)));
        scopes.add(scope("root-skill", "team-root"));
        String targetSkillId = "root-skill";
        String targetVersion = "1.0.0";
        for (int depth = 1; depth <= 9; depth++) {
            String skillId = "depth-skill-%02d".formatted(depth);
            String hash = hashFor(depth);
            skills.add(skill(skillId, "1.0.0"));
            versions.add(version(skillId, "1.0.0", "package-" + skillId, hash));
            releases.add(release("release-depth-%02d".formatted(depth), skillId, "1.0.0", hash));
            scopes.add(scope(skillId, "team-depth"));
            relations.add(new SkillLifecycleRelationRow(
                    "relation-depth-%02d".formatted(depth),
                    skillId,
                    "1.0.0",
                    targetSkillId,
                    targetVersion,
                    SkillRelationType.DEPENDS_ON,
                    SkillRelationStatus.ACTIVE,
                    AT,
                    null));
            targetSkillId = skillId;
        }
        return new SkillLifecycleProjectionSnapshot(
                "2".repeat(64),
                AT,
                List.copyOf(skills),
                List.copyOf(versions),
                List.copyOf(releases),
                List.copyOf(scopes),
                List.copyOf(relations));
    }

    private SkillLifecycleSkillRow skill(String skillId, String version) {
        return new SkillLifecycleSkillRow(skillId, version, "PUBLISHED", 1, 1, 1,
                SkillVisibility.PUBLIC, "team-a", 1);
    }

    private SkillLifecycleVersionRow version(String skillId, String version) {
        return version(skillId, version, "package-a", "a".repeat(64));
    }

    private SkillLifecycleVersionRow version(String skillId, String version, String packageId, String sha256) {
        return new SkillLifecycleVersionRow(skillId, version, packageId, "PUBLISHED",
                sha256, 12L, "uploader", AT, AT, "LOW");
    }

    private SkillLifecycleReleaseRow release(String releaseId, String skillId, String version) {
        return release(releaseId, skillId, version, "b".repeat(64));
    }

    private SkillLifecycleReleaseRow release(String releaseId, String skillId, String version, String sha256) {
        return new SkillLifecycleReleaseRow(releaseId, skillId, version, ReleaseEnvironment.PRODUCTION,
                ReleaseStatus.PROMOTED, "PASSED", sha256, AT, AT, AT,
                "assessment-a", "", "", "");
    }

    private SkillLifecycleScopeRow scope(String skillId) {
        return scope(skillId, "team-a");
    }

    private SkillLifecycleScopeRow scope(String skillId, String ownerTeamId) {
        return new SkillLifecycleScopeRow(skillId, SkillVisibility.PUBLIC, ownerTeamId, 2, 1, AT, AT);
    }

    private SkillLifecycleRelationRow relation(String relationId, String version) {
        return new SkillLifecycleRelationRow(relationId, "skill-a", version, "skill-b", "2.0.0",
                SkillRelationType.DEPENDS_ON, SkillRelationStatus.ACTIVE, AT, null);
    }

    private static String hashFor(int value) {
        return "%064x".formatted(value);
    }

    private static final class GuardedJdbcTemplate extends JdbcTemplate {
        private GuardedJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper, Object... args) {
            if (sql.contains("from skill_lifecycle_release_projection")
                    && !sql.contains("(skill_id, version) in")
                    && !sql.contains("skill_id = ?")) {
                throw new AssertionError("release lookup must stay bounded to discovered versions");
            }
            return super.query(sql, rowMapper, args);
        }
    }
}
