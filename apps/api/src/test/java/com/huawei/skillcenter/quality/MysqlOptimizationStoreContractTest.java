package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlOptimizationStoreContractTest {
    @Test
    void departmentOptimizationAdaptersUseMysqlDocumentTable() throws Exception {
        assertMysql("src/main/java/com/huawei/skillcenter/quality/MysqlOptimizationWorkItemStore.java", "OptimizationWorkItemBackendCondition.Mysql.class");
        assertMysql("src/main/java/com/huawei/skillcenter/quality/MysqlOptimizationExperimentStore.java", "OptimizationExperimentBackendCondition.Mysql.class");
        assertMysql("src/main/java/com/huawei/skillcenter/quality/MysqlOptimizationExperimentObservationStore.java", "OptimizationExperimentBackendCondition.Mysql.class");
        assertMysql("src/main/java/com/huawei/skillcenter/quality/MysqlOptimizationExperimentAssessmentStore.java", "OptimizationExperimentBackendCondition.Mysql.class");
    }

    private void assertMysql(String relative, String condition) throws Exception {
        Path source = projectPath(relative);
        assertThat(Files.exists(source)).isTrue();
        String implementation = Files.readString(source);
        assertThat(implementation).contains(condition);
        assertThat(implementation).contains("department_json_documents");
        assertThat(implementation).doesNotContain("::jsonb");
    }

    private Path projectPath(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("apps/api").resolve(relative);
    }
}
