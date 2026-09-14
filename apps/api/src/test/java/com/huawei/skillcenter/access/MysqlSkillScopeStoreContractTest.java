package com.huawei.skillcenter.access;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlSkillScopeStoreContractTest {
    @Test
    void departmentScopeAdapterUsesMysqlDocumentTableWithoutPostgresSyntax() throws Exception {
        Path source = projectPath("src/main/java/com/huawei/skillcenter/access/MysqlSkillScopeStore.java");
        assertThat(Files.exists(source)).isTrue();
        String implementation = Files.readString(source);
        assertThat(implementation).contains("SkillScopeBackendCondition.Mysql.class");
        assertThat(implementation).contains("department_json_documents");
        assertThat(implementation).doesNotContain("::jsonb");
    }

    private Path projectPath(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("apps/api").resolve(relative);
    }
}
