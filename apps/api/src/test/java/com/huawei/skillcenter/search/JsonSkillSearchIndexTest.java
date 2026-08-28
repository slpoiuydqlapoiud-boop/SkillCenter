package com.huawei.skillcenter.search;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSkillSearchIndexTest {
    @Test
    void searchesSkillIdNameTagsDescriptionTeamAndCategoryWithStableWeights() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        index.rebuild(List.of(document("eox-query", "EOX 查询", "release helper",
                List.of("release"), "platform", "efficiency")), "hash-1");

        List<SkillSearchHit> hits = index.search(SkillSearchQuery.of("EOX", "", "", "", "relevance"));

        assertThat(hits).extracting(SkillSearchHit::skillId).containsExactly("eox-query");
        assertThat(hits.getFirst().matchedFields()).contains("id", "name");
    }

    @Test
    void failedRebuildKeepsTheLastCommittedIndexAndSameHashIsIdempotent() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        index.rebuild(List.of(document("skill-a", "A", "safe", List.of(), "team", "other")), "hash-1");
        int revision = index.status().revision();

        assertThatThrownBy(() -> index.rebuild(java.util.Collections.singletonList(null), "hash-2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(index.search(SkillSearchQuery.of("skill-a", "", "", "", "relevance")))
                .hasSize(1);
        assertThat(index.rebuild(List.of(document("skill-a", "A", "safe", List.of(), "team", "other")), "hash-1")
                .revision()).isEqualTo(revision);
    }

    @Test
    void normalizesCaseAndWhitespaceAndMatchesEachSearchableField() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        index.rebuild(List.of(
                document("id-field", "Unrelated", "unrelated", List.of(), "other", "other"),
                document("other", "Name Field", "unrelated", List.of(), "other", "other"),
                document("split-fields", "Name", "Field", List.of(), "other", "other"),
                document("other-tags", "Unrelated", "unrelated", List.of("tag field"), "other", "other"),
                document("other-description", "Unrelated", "Description Field", List.of(), "other", "other"),
                document("other-team", "Unrelated", "unrelated", List.of(), "Team Field", "other"),
                document("other-category", "Unrelated", "unrelated", List.of(), "other", "Category Field")), "hash-1");

        assertThat(index.search(SkillSearchQuery.of("  ID-FIELD  ", "", "", "", "relevance")))
                .extracting(SkillSearchHit::skillId).containsExactly("id-field");
        assertThat(index.search(SkillSearchQuery.of("name", "", "", "", "relevance")))
                .extracting(SkillSearchHit::skillId).containsExactly("other", "split-fields");
        assertThat(index.search(SkillSearchQuery.of("name field", "", "", "", "relevance")))
                .extracting(SkillSearchHit::skillId).containsExactly("split-fields", "other");
        assertThat(index.search(SkillSearchQuery.of("tag", "", "", "", "relevance")))
                .extracting(SkillSearchHit::skillId).containsExactly("other-tags");
        assertThat(index.search(SkillSearchQuery.of("description", "", "", "", "relevance")))
                .extracting(SkillSearchHit::skillId).containsExactly("other-description");
        assertThat(index.search(SkillSearchQuery.of("team", "", "", "", "relevance")))
                .extracting(SkillSearchHit::skillId).containsExactly("other-team");
        assertThat(index.search(SkillSearchQuery.of("category", "", "", "", "relevance")))
                .extracting(SkillSearchHit::skillId).containsExactly("other-category");
    }

    @Test
    void appliesFiltersBeforeScoringAndReturnsZeroScoreCandidatesForEmptyText() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        index.rebuild(List.of(
                document("published-low", "Common", "description", List.of(), "alpha", "tools", "published", "low", Instant.parse("2026-01-02T00:00:00Z")),
                document("deprecated-high", "Common", "description", List.of(), "beta", "other", "deprecated", "high", Instant.parse("2026-01-01T00:00:00Z"))), "hash-1");

        assertThat(index.search(SkillSearchQuery.of("common", "tools", "published", "low", "relevance")))
                .extracting(SkillSearchHit::skillId).containsExactly("published-low");
        assertThat(index.search(SkillSearchQuery.of("", "", "", "", "relevance")))
                .extracting(SkillSearchHit::skillId).containsExactly("published-low", "deprecated-high");
        assertThat(index.search(SkillSearchQuery.of("", "tools", "published", "low", "relevance")))
                .allSatisfy(hit -> {
                    assertThat(hit.score()).isZero();
                    assertThat(hit.matchedFields()).isEmpty();
                })
                .extracting(SkillSearchHit::skillId).containsExactly("published-low");
        assertThat(index.search(SkillSearchQuery.of("missing", "", "", "", "relevance"))).isEmpty();
    }

    @Test
    void searchesUseOneCommittedSnapshotWhileRebuildsRunConcurrently() throws Exception {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        List<SkillSearchDocument> first = java.util.stream.IntStream.range(0, 5_000)
                .mapToObj(number -> document("first-" + number, "Common", "description", List.of(), "team", "category"))
                .toList();
        List<SkillSearchDocument> second = java.util.stream.IntStream.range(0, 5_000)
                .mapToObj(number -> document("second-" + number, "Common", "description", List.of(), "team", "category"))
                .toList();
        index.rebuild(first, "first");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<SkillSearchHit>> search = executor.submit(
                    () -> index.search(SkillSearchQuery.of("common", "", "", "", "relevance")));
            Future<?> rebuild = executor.submit((Callable<Void>) () -> {
                for (int revision = 0; revision < 20; revision++) {
                    index.rebuild(revision % 2 == 0 ? second : first, "hash-" + revision);
                }
                return null;
            });

            List<SkillSearchHit> hits = search.get();
            rebuild.get();

            assertThat(hits).hasSize(5_000);
            assertThat(hits).allSatisfy(hit -> assertThat(hit.skillId()).matches("^(first|second)-.*"));
        }
    }

    @Test
    void breaksTiesByStatusThenLastUpdatedThenSkillIdAndCapsCandidates() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        index.rebuild(List.of(
                document("z-last", "Same", "description", List.of(), "team", "category", "published", "low", Instant.parse("2026-01-01T00:00:00Z")),
                document("a-first", "Same", "description", List.of(), "team", "category", "published", "low", Instant.parse("2026-01-02T00:00:00Z")),
                document("deprecated", "Same", "description", List.of(), "team", "category", "deprecated", "low", Instant.parse("2026-12-01T00:00:00Z"))), "hash-1");

        assertThat(index.search(SkillSearchQuery.of("same", "", "", "", "relevance")))
                .extracting(SkillSearchHit::skillId).containsExactly("a-first", "z-last", "deprecated");

        List<SkillSearchDocument> documents = java.util.stream.IntStream.range(0, 5_001)
                .mapToObj(number -> document("cap-" + number, "Cap", "description", List.of(), "team", "category"))
                .toList();
        index.rebuild(documents, "hash-2");
        assertThat(index.search(SkillSearchQuery.of("cap", "", "", "", "relevance"))).hasSize(5_000);
    }

    @Test
    void invalidationMarksTheCommittedIndexStaleWithoutRemovingHits() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        index.rebuild(List.of(document("skill-a", "A", "safe", List.of(), "team", "other")), "hash-1");

        index.invalidate("source-updated");

        assertThat(index.status().state()).isEqualTo("STALE");
        assertThat(index.search(SkillSearchQuery.of("skill-a", "", "", "", "relevance"))).hasSize(1);
    }

    private static SkillSearchDocument document(String skillId, String name, String description, List<String> tags,
                                                String team, String category) {
        return document(skillId, name, description, tags, team, category, "published", "low", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static SkillSearchDocument document(String skillId, String name, String description, List<String> tags,
                                                String team, String category, String status, String risk, Instant lastUpdated) {
        return new SkillSearchDocument(skillId, name, description, tags, team, category, status, risk, lastUpdated,
                Instant.parse("2026-01-01T00:00:00Z"), "1.0.0", "PUBLIC", "team");
    }
}
