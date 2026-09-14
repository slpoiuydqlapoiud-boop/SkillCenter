package com.huawei.skillcenter.relationship;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlSkillRelationStoreContractTest {
    @Test
    void departmentRelationAdapterUsesMysqlDocumentTableWithoutPostgresSyntax() throws Exception {
        Path source = projectPath("src/main/java/com/huawei/skillcenter/relationship/MysqlSkillRelationStore.java");
        assertThat(Files.exists(source)).isTrue();
        String implementation = Files.readString(source);
        assertThat(implementation).contains("SkillRelationBackendCondition.Mysql.class");
        assertThat(implementation).contains("department_json_documents");
        assertThat(implementation).doesNotContain("::jsonb");
    }

    private Path projectPath(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("apps/api").resolve(relative);
    }
}
