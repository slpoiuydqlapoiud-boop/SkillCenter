package com.huawei.skillcenter.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SkillLifecycleSecurityEvidenceMigrationTest {
    @Test
    void v3MigrationAddsMetadataOnlySecurityEvidenceProjection() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V3__add_skill_lifecycle_security_evidence.sql");
        assertThat(resource.exists()).isTrue();
        String migration = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("alter table skill_lifecycle_version_projection")
                .contains("security_status")
                .contains("security_scanner_id")
                .contains("security_scanner_version")
                .contains("create table skill_lifecycle_version_security_finding_projection")
                .contains("finding_code")
                .contains("finding_path")
                .contains("finding_severity")
                .contains("length(finding_path) between 1 and 512")
                .contains("security_scanner_id ~")
                .contains("schema_version = 3")
                .doesNotContain("reason", "secret", "prompt", "trace");
    }
}
