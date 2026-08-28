package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernanceBackendConfigurationTest {
    @TempDir
    Path tempDir;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Fixture.class, JsonGovernanceStateRepository.class,
                    JdbcGovernanceStateRepository.class);

    @Test
    void jsonIsTheDefaultGovernanceBackend() {
        PersistenceControlProperties properties = new PersistenceControlProperties();

        assertThat(properties.normalizedGovernanceBackend()).isEqualTo("json");
    }

    @Test
    void postgresqlGovernanceRequiresPostgresqlPersistence() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setGovernanceBackend("postgresql");

        assertThatThrownBy(() -> properties.validate(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("governanceBackend=postgresql requires backend=postgresql");
    }

    @Test
    void migrationStoresVersionedGovernanceAggregate() throws Exception {
        String migration = new ClassPathResource("db/migration/V7__create_governance_state.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_governance_state")
                .contains("state_key varchar(64) PRIMARY KEY")
                .contains("state jsonb NOT NULL")
                .contains("revision bigint NOT NULL");
    }

    @Test
    void migrationAddsRelationalSkillVersionFactTable() throws Exception {
        String migration = new ClassPathResource("db/migration/V8__create_governance_skill_versions.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_governance_version")
                .contains("package_id text PRIMARY KEY")
                .contains("UNIQUE (skill_id, version)")
                .contains("security_evidence jsonb NOT NULL");
    }

    @Test
    void migrationAddsRelationalReviewFactTable() throws Exception {
        String migration = new ClassPathResource("db/migration/V9__create_governance_reviews.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_governance_review")
                .contains("review_id text PRIMARY KEY")
                .contains("security_evidence jsonb NOT NULL")
                .contains("skill_governance_review_status_idx");
    }

    @Test
    void migrationAddsRelationalGovernanceConfigurationFacts() throws Exception {
        String migration = new ClassPathResource("db/migration/V10__create_governance_configuration.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_governance_team")
                .contains("CREATE TABLE skill_governance_role_binding")
                .contains("CREATE TABLE skill_governance_collection")
                .contains("CREATE TABLE skill_governance_policy")
                .contains("member_user_ids jsonb NOT NULL")
                .contains("page_size_options jsonb NOT NULL");
    }

    @Test
    void backendSelectionWiresExactlyOneRepository() {
        context.withPropertyValues(
                        "skill-center.governance-backend= JSON ",
                        "skill-center.governance-storage=" + tempDir.resolve("governance.json"))
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(GovernanceStateRepository.class)).hasSize(1);
                    assertThat(application).hasSingleBean(JsonGovernanceStateRepository.class);
                    assertThat(application.getBeansOfType(JdbcGovernanceStateRepository.class)).isEmpty();
                });

        context.withPropertyValues(
                        "skill-center.persistence.backend= PostgreSQL ",
                        "skill-center.governance-backend= PostgreSQL ")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(GovernanceStateRepository.class)).hasSize(1);
                    assertThat(application).hasSingleBean(JdbcGovernanceStateRepository.class);
                    assertThat(application.getBeansOfType(JsonGovernanceStateRepository.class)).isEmpty();
                });
    }

    @Configuration
    static class Fixture {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return org.mockito.Mockito.mock(JdbcTemplate.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return org.mockito.Mockito.mock(PlatformTransactionManager.class);
        }
    }
}
