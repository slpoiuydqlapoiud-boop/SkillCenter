package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationExperimentBackendConfigurationTest {
    @TempDir
    Path tempDir;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Fixture.class, OptimizationExperimentStore.class,
                    OptimizationExperimentObservationStore.class, OptimizationExperimentAssessmentStore.class,
                    JdbcOptimizationExperimentStore.class, JdbcOptimizationExperimentObservationStore.class,
                    JdbcOptimizationExperimentAssessmentStore.class);

    @Test
    void jsonIsTheDefaultOptimizationExperimentBackend() {
        PersistenceControlProperties properties = new PersistenceControlProperties();

        assertThat(properties.normalizedOptimizationExperimentBackend()).isEqualTo("json");
    }

    @Test
    void postgresqlOptimizationExperimentsRequirePostgresqlPersistence() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setOptimizationExperimentBackend("postgresql");

        assertThatThrownBy(() -> properties.validate(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("optimizationExperimentBackend=postgresql requires backend=postgresql");
    }

    @Test
    void migrationCreatesSharedOptimizationExperimentEvidenceTables() throws Exception {
        String migration = new ClassPathResource("db/migration/V12__create_optimization_experiment_state.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_optimization_experiments")
                .contains("CREATE TABLE skill_optimization_experiment_observations")
                .contains("CREATE TABLE skill_optimization_experiment_assessments")
                .contains("WHERE status IN ('QUEUED', 'RUNNING')");
    }

    @Test
    void backendSelectionWiresExactlyOneRepositorySet() {
        context.withPropertyValues("skill-center.optimization-experiment-backend= JSON ")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(OptimizationExperimentRepository.class)).hasSize(1);
                    assertThat(application.getBeansOfType(OptimizationExperimentObservationRepository.class)).hasSize(1);
                    assertThat(application.getBeansOfType(OptimizationExperimentAssessmentRepository.class)).hasSize(1);
                    assertThat(application).hasSingleBean(OptimizationExperimentStore.class);
                    assertThat(application.getBeansOfType(JdbcOptimizationExperimentStore.class)).isEmpty();
                });

        context.withPropertyValues(
                        "skill-center.persistence.backend= PostgreSQL ",
                        "skill-center.optimization-experiment-backend= PostgreSQL ")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(OptimizationExperimentRepository.class)).hasSize(1);
                    assertThat(application.getBeansOfType(OptimizationExperimentObservationRepository.class)).hasSize(1);
                    assertThat(application.getBeansOfType(OptimizationExperimentAssessmentRepository.class)).hasSize(1);
                    assertThat(application).hasSingleBean(JdbcOptimizationExperimentStore.class);
                    assertThat(application.getBeansOfType(OptimizationExperimentStore.class)).isEmpty();
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
