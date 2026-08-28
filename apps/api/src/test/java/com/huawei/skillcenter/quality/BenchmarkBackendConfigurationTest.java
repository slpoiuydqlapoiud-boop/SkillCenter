package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
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

class BenchmarkBackendConfigurationTest {
    @TempDir
    Path tempDir;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Fixture.class, BenchmarkStore.class, JdbcBenchmarkStore.class);

    @Test
    void jsonIsTheDefaultBenchmarkBackend() {
        PersistenceControlProperties properties = new PersistenceControlProperties();

        assertThat(properties.normalizedBenchmarkBackend()).isEqualTo("json");
    }

    @Test
    void postgresqlBenchmarkBackendRequiresGlobalPostgresqlPersistence() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setBenchmarkBackend("postgresql");

        assertThatThrownBy(() -> properties.validate(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("benchmarkBackend=postgresql requires backend=postgresql");
    }

    @Test
    void migrationCreatesSharedBenchmarkTableAndExperimentUniqueness() throws Exception {
        String migration = new ClassPathResource("db/migration/V13__create_benchmarks.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_benchmarks")
                .contains("UNIQUE INDEX skill_benchmarks_experiment")
                .contains("jsonb_typeof(payload) = 'object'");
    }

    @Test
    void backendSelectionWiresExactlyOneRepository() {
        context.withPropertyValues("skill-center.benchmark-backend= JSON ")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(BenchmarkRepository.class)).hasSize(1);
                    assertThat(application).hasSingleBean(BenchmarkStore.class);
                    assertThat(application.getBeansOfType(JdbcBenchmarkStore.class)).isEmpty();
                });

        context.withPropertyValues(
                        "skill-center.persistence.backend= PostgreSQL ",
                        "skill-center.benchmark-backend= PostgreSQL ")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBeansOfType(BenchmarkRepository.class)).hasSize(1);
                    assertThat(application).hasSingleBean(JdbcBenchmarkStore.class);
                    assertThat(application.getBeansOfType(BenchmarkStore.class)).isEmpty();
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
