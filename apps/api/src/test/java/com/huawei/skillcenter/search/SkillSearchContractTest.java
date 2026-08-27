package com.huawei.skillcenter.search;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void queryRejectsInvalidStatusAndRiskFilters() {
        assertThatThrownBy(() -> SkillSearchQuery.of("text", "", "pending", "", "updated"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillSearchQuery.of("text", "", "", "critical", "updated"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> SkillSearchQuery.of("text", "", "", "", "updated"))
                .doesNotThrowAnyException();
    }

    @Test
    void searchContractsValidateBoundsAndCopyCollections() {
        assertThatThrownBy(() -> new SkillSearchDocument(
                "x".repeat(129), "Name", "description", List.of(), "team", "other",
                "published", "low", Instant.now(), Instant.now(), "1.0.0", "PUBLIC", "team"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillSearchDocument(
                "skill-a", "Name", "description", List.of(), "team", "other",
                "pending", "low", Instant.now(), Instant.now(), "1.0.0", "PUBLIC", "team"))
                .isInstanceOf(IllegalArgumentException.class);

        List<String> tags = new ArrayList<>(List.of("tag"));
        SkillSearchDocument document = validDocument(tags);
        tags.add("later");
        assertThat(document.tags()).containsExactly("tag");
        assertThatThrownBy(() -> document.tags().add("blocked"))
                .isInstanceOf(UnsupportedOperationException.class);

        List<String> matchedFields = new ArrayList<>(List.of("name"));
        SkillSearchHit hit = new SkillSearchHit("skill-a", 1.0, matchedFields);
        matchedFields.add("tags");
        assertThat(hit.matchedFields()).containsExactly("name");
        assertThatThrownBy(() -> new SkillSearchHit("skill-a", 1.0, List.of("prompt")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillSearchHit("skill-a", 1.0,
                List.of("id", "name", "tags", "description", "team", "category", "extra")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotsAndRebuildResultsValidateAndRemainImmutable() {
        List<SkillSearchDocument> documents = new ArrayList<>(List.of(validDocument(List.of("tag"))));
        SkillSearchDocumentSnapshot snapshot = new SkillSearchDocumentSnapshot(documents, "hash", 1L);
        documents.clear();
        assertThat(snapshot.documents()).hasSize(1);
        assertThatThrownBy(() -> snapshot.documents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new SkillSearchDocumentSnapshot(List.of(validDocument(List.of())), "hash", -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillSearchIndexStatus("UNKNOWN", 0, 0, "hash", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillSearchRebuildResult(-1, 0, "hash", "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SkillSearchDocument validDocument(List<String> tags) {
        return new SkillSearchDocument("skill-a", "Name", "description", tags, "team", "other",
                "published", "low", Instant.now(), Instant.now(), "1.0.0", "PUBLIC", "team");
    }
}
