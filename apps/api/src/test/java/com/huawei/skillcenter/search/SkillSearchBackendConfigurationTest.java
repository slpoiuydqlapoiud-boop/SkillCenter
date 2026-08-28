package com.huawei.skillcenter.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.access.SkillScopeRepository;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import com.huawei.skillcenter.skill.SkillRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SkillSearchBackendConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Fixture.class, SkillSearchCatalogConfiguration.class);

    @Test
    void jsonIsTheDefaultSearchBackend() {
        PersistenceControlProperties properties = new PersistenceControlProperties();

        assertThat(properties.normalizedSearchIndexBackend()).isEqualTo("json");
    }

    @Test
    void postgresqlSearchBackendRequiresGlobalPostgresqlPersistence() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setSearchIndexBackend("postgresql");

        assertThatThrownBy(() -> properties.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("searchIndexBackend=postgresql requires backend=postgresql");
    }

    @Test
    void migrationCreatesVersionedSharedSearchProjection() throws Exception {
        String migration = new ClassPathResource("db/migration/V16__create_skill_search_index.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_search_index_state")
                .contains("CREATE TABLE skill_search_documents")
                .contains("PRIMARY KEY (skill_id)")
                .contains("CREATE INDEX skill_search_documents_search_tokens");
    }

    @Test
    void backendSelectionWiresExactlyOneSearchIndex() {
        context.withPropertyValues("skill-center.search-index-backend= JSON ")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(SkillSearchIndex.class)).hasSize(1);
                    assertThat(application).hasSingleBean(JsonSkillSearchIndex.class);
                    assertThat(application.getBeansOfType(JdbcSkillSearchIndex.class)).isEmpty();
                });

        context.withPropertyValues(
                        "skill-center.persistence.backend= PostgreSQL ",
                        "skill-center.search-index-backend= PostgreSQL ")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(SkillSearchIndex.class)).hasSize(1);
                    assertThat(application).hasSingleBean(JdbcSkillSearchIndex.class);
                    assertThat(application.getBeansOfType(JsonSkillSearchIndex.class)).isEmpty();
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
            return mock(JdbcTemplate.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        GovernanceStore governanceStore() {
            return mock(GovernanceStore.class);
        }

        @Bean
        SkillRepository skillRepository() {
            return mock(SkillRepository.class);
        }

        @Bean
        SkillScopeRepository skillScopeRepository() {
            return mock(SkillScopeRepository.class);
        }
    }
}
