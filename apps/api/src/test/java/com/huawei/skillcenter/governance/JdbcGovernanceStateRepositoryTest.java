package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcGovernanceStateRepositoryTest {
    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbc;
    private boolean dockerAvailable;

    @BeforeAll
    void startPostgres() {
        dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        if (!dockerAvailable) return;
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearState() {
        if (dockerAvailable) {
            jdbc.update("delete from skill_governance_policy");
            jdbc.update("delete from skill_governance_collection");
            jdbc.update("delete from skill_governance_tag");
            jdbc.update("delete from skill_governance_category");
            jdbc.update("delete from skill_governance_role_binding");
            jdbc.update("delete from skill_governance_team");
            jdbc.update("delete from skill_governance_review");
            jdbc.update("delete from skill_governance_version");
            jdbc.update("delete from skill_governance_state");
        }
    }

    @Test
    void stateRoundTripsAcrossRepositoryRestartAndAdvancesRevision() {
        requireDocker();
        JdbcGovernanceStateRepository store = store();
        GovernanceSnapshot snapshot = new GovernanceSnapshot(List.of(), List.of(), List.of(),
                List.of(new AuditEvent("audit-1", "TEST", "SKILL", "skill-1", "admin", "admin",
                        "request-1", java.time.Instant.parse("2026-08-25T00:00:00Z"), java.util.Map.of())));

        GovernanceStateRepository.GovernanceState seeded = store.loadOrSeed(GovernanceSnapshot::empty);
        assertThat(seeded.revision()).isZero();
        GovernanceStateRepository.GovernanceState saved = store.replace(seeded.revision(), snapshot);

        assertThat(saved.revision()).isEqualTo(1L);
        assertThat(store.load()).contains(saved);
        assertThat(jdbc.queryForObject("select revision from skill_governance_state where state_key = ?",
                Long.class, "governance-state")).isEqualTo(1L);
    }

    @Test
    void staleRevisionIsRejectedWithoutOverwritingStoredState() {
        requireDocker();
        JdbcGovernanceStateRepository store = store();
        GovernanceStateRepository.GovernanceState seeded = store.loadOrSeed(GovernanceSnapshot::empty);
        GovernanceStateRepository.GovernanceState saved = store.replace(seeded.revision(), GovernanceSnapshot.empty());

        assertThatThrownBy(() -> store.replace(seeded.revision(),
                new GovernanceSnapshot(List.of(), List.of(), List.of(), List.of(
                        new AuditEvent("audit-stale", "STALE", "SKILL", "skill-1", "admin", "admin",
                                "request-stale", java.time.Instant.parse("2026-08-25T00:00:00Z"), java.util.Map.of())))))
                .isInstanceOf(GovernanceStateConflictException.class);
        assertThat(store.load()).contains(saved);
    }

    @Test
    void skillVersionsRoundTripThroughRelationalFactsAndBackfillLegacyAggregate() {
        requireDocker();
        JdbcGovernanceStateRepository store = store();
        SkillVersion version = version("published");
        GovernanceSnapshot snapshot = new GovernanceSnapshot(List.of(version), List.of(), List.of(), List.of());

        GovernanceStateRepository.GovernanceState seeded = store.loadOrSeed(() -> snapshot);
        assertThat(seeded.snapshot().versions()).containsExactly(version);
        assertThat(jdbc.queryForObject("select count(*) from skill_governance_version", Long.class)).isEqualTo(1L);

        jdbc.update("update skill_governance_version set status = ? where package_id = ?", "deprecated", "pkg-1");
        assertThat(store.load().orElseThrow().snapshot().versions().get(0).status()).isEqualTo("deprecated");
    }

    @Test
    void versionRowsAreUpdatedInTheSameRevisionTransaction() {
        requireDocker();
        JdbcGovernanceStateRepository store = store();
        GovernanceStateRepository.GovernanceState seeded = store.loadOrSeed(GovernanceSnapshot::empty);
        GovernanceSnapshot snapshot = new GovernanceSnapshot(List.of(version("published")), List.of(), List.of(), List.of());

        store.replace(seeded.revision(), snapshot);

        assertThat(jdbc.queryForObject("select status from skill_governance_version where package_id = ?",
                String.class, "pkg-1")).isEqualTo("published");
        assertThat(jdbc.queryForObject("select revision from skill_governance_state where state_key = ?",
                Long.class, "governance-state")).isEqualTo(1L);
    }

    @Test
    void reviewsRoundTripThroughRelationalFactsAndBackfillLegacyAggregate() {
        requireDocker();
        JdbcGovernanceStateRepository store = store();
        ReviewTask review = review("pending");
        GovernanceSnapshot snapshot = new GovernanceSnapshot(List.of(), List.of(review), List.of(), List.of());

        GovernanceStateRepository.GovernanceState seeded = store.loadOrSeed(() -> snapshot);
        assertThat(seeded.snapshot().reviews()).containsExactly(review);
        assertThat(jdbc.queryForObject("select count(*) from skill_governance_review", Long.class)).isEqualTo(1L);

        jdbc.update("update skill_governance_review set status = ? where review_id = ?", "approved", "review-1");
        assertThat(store.load().orElseThrow().snapshot().reviews().get(0).status()).isEqualTo("approved");
    }

    @Test
    void reviewRowsAreUpdatedInTheSameRevisionTransaction() {
        requireDocker();
        JdbcGovernanceStateRepository store = store();
        GovernanceStateRepository.GovernanceState seeded = store.loadOrSeed(GovernanceSnapshot::empty);
        GovernanceSnapshot snapshot = new GovernanceSnapshot(List.of(), List.of(review("pending")), List.of(), List.of());

        store.replace(seeded.revision(), snapshot);

        assertThat(jdbc.queryForObject("select status from skill_governance_review where review_id = ?",
                String.class, "review-1")).isEqualTo("pending");
        assertThat(jdbc.queryForObject("select revision from skill_governance_state where state_key = ?",
                Long.class, "governance-state")).isEqualTo(1L);
    }

    @Test
    void governanceConfigurationRoundTripsThroughRelationalFactsAndBackfillsLegacyAggregate() {
        requireDocker();
        JdbcGovernanceStateRepository store = store();
        GovernanceConfiguration configuration = configuration();
        GovernanceSnapshot snapshot = new GovernanceSnapshot(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), configuration);

        GovernanceStateRepository.GovernanceState seeded = store.loadOrSeed(() -> snapshot);
        assertThat(seeded.snapshot().configuration().teams()).extracting(TeamDefinition::teamId)
                .containsExactly("team-a");
        assertThat(jdbc.queryForObject("select count(*) from skill_governance_team", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from skill_governance_policy", Long.class)).isEqualTo(1L);

        jdbc.update("update skill_governance_team set status = ? where team_id = ?", "inactive", "team-a");
        assertThat(store.load().orElseThrow().snapshot().configuration().teams().get(0).status())
                .isEqualTo("inactive");
    }

    @Test
    void configurationRowsAreUpdatedInTheSameRevisionTransaction() {
        requireDocker();
        JdbcGovernanceStateRepository store = store();
        GovernanceStateRepository.GovernanceState seeded = store.loadOrSeed(GovernanceSnapshot::empty);
        GovernanceConfiguration configuration = configuration();
        GovernanceSnapshot snapshot = new GovernanceSnapshot(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), configuration);

        store.replace(seeded.revision(), snapshot);

        assertThat(jdbc.queryForObject("select name from skill_governance_team where team_id = ?",
                String.class, "team-a")).isEqualTo("Team A");
        assertThat(jdbc.queryForObject("select revision from skill_governance_state where state_key = ?",
                Long.class, "governance-state")).isEqualTo(1L);
    }

    private JdbcGovernanceStateRepository store() {
        return new JdbcGovernanceStateRepository(jdbc, new ObjectMapper().findAndRegisterModules(),
                new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private SkillVersion version(String status) {
        return new SkillVersion("pkg-1", "skill-1", "1.0.0", status, "a".repeat(64), 42,
                "local://pkg-1", "uploader", java.time.Instant.parse("2026-08-25T00:00:00Z"),
                "publisher", java.time.Instant.parse("2026-08-25T00:01:00Z"), "review-1",
                "", "", "publisher", java.time.Instant.parse("2026-08-25T00:01:00Z"),
                "high", new SecurityScanEvidence("PASSED", "scanner", "1.2.3",
                        List.of(new SecurityScanEvidence.Finding("NO_SECRET", "skill.json", "INFO"))));
    }

    private ReviewTask review(String status) {
        return new ReviewTask("review-1", "pkg-1", "skill-1", "1.0.0", status, "uploader",
                java.time.Instant.parse("2026-08-25T00:00:00Z"), "", null, "", "high", "", null, "",
                new SecurityScanEvidence("PASSED", "scanner", "1.2.3", List.of()));
    }

    private GovernanceConfiguration configuration() {
        return new GovernanceConfiguration(
                List.of(new TeamDefinition("team-a", "Team A", "desc", "owner-1", List.of("owner-1"),
                        "active", java.time.Instant.parse("2026-08-25T00:00:00Z"),
                        java.time.Instant.parse("2026-08-25T00:01:00Z"))),
                List.of(new RoleBinding("owner-1", "admin", "team-a", "active", "admin",
                        java.time.Instant.parse("2026-08-25T00:01:00Z"))),
                List.of(new CategoryDefinition("analysis", "Analysis", "desc", 1, "active", "admin",
                        java.time.Instant.parse("2026-08-25T00:01:00Z"))),
                List.of(new TagDefinition("safe", "Safe", "desc", 1, "active", "admin",
                        java.time.Instant.parse("2026-08-25T00:01:00Z"))),
                List.of(new CollectionDefinition("featured", "Featured", "desc", "team-a", "team",
                        List.of("skill-1"), 1, "active", "admin",
                        java.time.Instant.parse("2026-08-25T00:01:00Z"))),
                new PlatformPolicy(2, List.of(12, 24), 24, "1.2.3", "team", "admin",
                        java.time.Instant.parse("2026-08-25T00:01:00Z")));
    }

    private void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable,
                "CAPABILITY_SKIP: Docker is unavailable; PostgreSQL governance integration cannot run");
    }
}
