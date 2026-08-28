package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
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
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionEvidenceBackendConfigurationTest {
    @TempDir
    Path tempDir;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Fixture.class, ProductionEvidenceStore.class, JdbcProductionEvidenceStore.class);

    @Test
    void jsonIsTheDefaultProductionEvidenceBackend() {
        PersistenceControlProperties properties = new PersistenceControlProperties();

        assertThat(properties.normalizedProductionEvidenceBackend()).isEqualTo("json");
    }

    @Test
    void postgresqlProductionEvidenceRequiresPostgresqlPersistence() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setProductionEvidenceBackend("postgresql");

        assertThatThrownBy(() -> properties.validate(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("productionEvidenceBackend=postgresql requires backend=postgresql");
    }

    @Test
    void migrationStoresSafeEvidenceMetadataAndRevision() throws Exception {
        String migration = new ClassPathResource("db/migration/V6__create_production_evidence.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_production_evidence")
                .contains("evidence_id varchar(64) PRIMARY KEY")
                .contains("revision bigint NOT NULL")
                .contains("expires_at timestamptz");
    }

    @Test
    void backendSelectionWiresExactlyOneRepository() {
        context.withPropertyValues("skill-center.production-evidence-backend= JSON ")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(ProductionEvidenceRepository.class)).hasSize(1);
                    assertThat(application).hasSingleBean(ProductionEvidenceStore.class);
                    assertThat(application.getBeansOfType(JdbcProductionEvidenceStore.class)).isEmpty();
                });

        context.withPropertyValues(
                        "skill-center.persistence.backend= PostgreSQL ",
                        "skill-center.production-evidence-backend= PostgreSQL ")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(ProductionEvidenceRepository.class)).hasSize(1);
                    assertThat(application).hasSingleBean(JdbcProductionEvidenceStore.class);
                    assertThat(application.getBeansOfType(ProductionEvidenceStore.class)).isEmpty();
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
