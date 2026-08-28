package com.huawei.skillcenter.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import com.huawei.skillcenter.relationship.JdbcSkillRelationStore;
import com.huawei.skillcenter.relationship.SkillRelationRepository;
import com.huawei.skillcenter.relationship.SkillRelationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillAssetBackendConfigurationTest {
    @TempDir
    Path tempDir;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Fixture.class, SkillScopeStore.class,
                    SkillRelationStore.class, JdbcSkillScopeStore.class, JdbcSkillRelationStore.class);

    @Test
    void jsonIsTheDefaultForSkillAssetStores() {
        PersistenceControlProperties properties = new PersistenceControlProperties();

        assertThat(properties.normalizedSkillScopeBackend()).isEqualTo("json");
        assertThat(properties.normalizedSkillRelationBackend()).isEqualTo("json");
    }

    @Test
    void postgresqlSkillAssetBackendRequiresGlobalPostgresqlPersistence() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setSkillScopeBackend("postgresql");

        assertThatThrownBy(() -> properties.validate(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("skillScopeBackend=postgresql requires backend=postgresql");
    }

    @Test
    void migrationsCreateSharedScopeAndRelationTables() throws Exception {
        String scopes = new ClassPathResource("db/migration/V14__create_skill_scopes.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String relations = new ClassPathResource("db/migration/V15__create_skill_relations.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(scopes)
                .contains("CREATE TABLE skill_scopes")
                .contains("skill_scopes_revision");
        assertThat(relations)
                .contains("CREATE TABLE skill_relations")
                .contains("skill_relations_active_business_key");
    }

    @Test
    void backendSelectionWiresExactlyOneScopeAndRelationRepository() {
        context.withPropertyValues(
                        "skill-center.skill-scope-backend=JSON",
                        "skill-center.skill-relation-backend=JSON")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(SkillScopeRepository.class)).hasSize(1);
                    assertThat(application.getBeansOfType(SkillRelationRepository.class)).hasSize(1);
                    assertThat(application).hasSingleBean(SkillScopeStore.class);
                    assertThat(application.getBeansOfType(JdbcSkillScopeStore.class)).isEmpty();
                    assertThat(application.getBeansOfType(JdbcSkillRelationStore.class)).isEmpty();
                });

        context.withPropertyValues(
                        "skill-center.persistence.backend=PostgreSQL",
                        "skill-center.skill-scope-backend=PostgreSQL",
                        "skill-center.skill-relation-backend=PostgreSQL")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(SkillScopeRepository.class)).hasSize(1);
                    assertThat(application.getBeansOfType(SkillRelationRepository.class)).hasSize(1);
                    assertThat(application.getBeansOfType(SkillScopeStore.class)).isEmpty();
                    assertThat(application).hasSingleBean(JdbcSkillScopeStore.class);
                    assertThat(application).hasSingleBean(JdbcSkillRelationStore.class);
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
