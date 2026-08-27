package com.huawei.skillcenter.search;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SkillSearchContractTest {
    @Test
    void searchContractsRejectUnboundedOrSensitiveDocumentFields() {
        assertThatCode(() -> new SkillSearchDocument(
                "skill-a", "Name", "description", List.of("tag"), "team", "other",
                "published", "low", Instant.now(), Instant.now(), "1.0.0", "PUBLIC", "team"))
                .doesNotThrowAnyException();
        assertThat(SkillSearchQuery.of("  EOX   查询  ", "", "", "", "updated").text())
                .isEqualTo("EOX 查询");
    }
}
