package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationWorkItemBackendConfigurationTest {
    @TempDir
    Path tempDir;

    @Test
    void jsonIsTheDefaultOptimizationWorkItemBackend() {
        PersistenceControlProperties properties = new PersistenceControlProperties();

        assertThat(properties.normalizedOptimizationWorkItemBackend()).isEqualTo("json");
    }

    @Test
    void postgresqlOptimizationWorkItemsRequirePostgresqlPersistence() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setOptimizationWorkItemBackend("postgresql");

        assertThatThrownBy(() -> properties.validate(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("optimizationWorkItemBackend=postgresql requires backend=postgresql");
    }

    @Test
    void migrationDefinesTheActiveBusinessKeyConstraint() throws Exception {
        String migration = new ClassPathResource("db/migration/V5__create_optimization_work_items.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_optimization_work_items")
                .contains("payload jsonb NOT NULL")
                .contains("WHERE status NOT IN ('COMPLETED', 'ABANDONED')");
    }
}
