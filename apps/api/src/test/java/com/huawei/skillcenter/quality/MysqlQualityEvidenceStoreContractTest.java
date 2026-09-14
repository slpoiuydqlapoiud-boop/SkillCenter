package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlQualityEvidenceStoreContractTest {
    @Test
    void departmentQualityEvidenceAdapterUsesTheMysqlDocumentTable() throws Exception {
        Path source = projectPath("src/main/java/com/huawei/skillcenter/quality/MysqlQualityEvidenceStore.java");
        assertThat(Files.exists(source)).isTrue();
        String implementation = Files.readString(source);
        assertThat(implementation).contains("QualityEvidenceBackendCondition.Mysql.class");
        assertThat(implementation).contains("department_json_documents");
        assertThat(implementation).doesNotContain("::jsonb");
    }

    @Test
    void mysqlMigrationCreatesJsonDocumentStorage() throws Exception {
        Path migration = projectPath("src/main/resources/db/migration-mysql/V2__create_department_json_documents.sql");
        assertThat(Files.exists(migration)).isTrue();
        String sql = Files.readString(migration).toLowerCase(java.util.Locale.ROOT);
        assertThat(sql).contains("department_json_documents");
        assertThat(sql).contains("json");
    }

    private Path projectPath(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("apps/api").resolve(relative);
    }
}
