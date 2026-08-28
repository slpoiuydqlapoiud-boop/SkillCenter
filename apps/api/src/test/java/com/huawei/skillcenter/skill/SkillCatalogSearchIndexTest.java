package com.huawei.skillcenter.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillNotVisibleException;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.search.SkillSearchDocument;
import com.huawei.skillcenter.search.SkillSearchDocumentSnapshot;
import com.huawei.skillcenter.search.SkillSearchDocumentSource;
import com.huawei.skillcenter.search.SkillSearchHit;
import com.huawei.skillcenter.search.SkillSearchIndex;
import com.huawei.skillcenter.search.SkillSearchIndexStatus;
import com.huawei.skillcenter.search.SkillSearchQuery;
import com.huawei.skillcenter.search.SkillSearchRebuildResult;
import com.huawei.skillcenter.search.SkillSearchRefreshCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillCatalogSearchIndexTest {
    @TempDir
    Path tempDir;

    @Test
    void searchesByIdThroughIndexedCandidatesWithoutRepositoryPageReads() {
        SkillRecord matching = skill("release-notes", "Release notes");
        RecordingRepository repository = new RecordingRepository();
        RecordingSource source = new RecordingSource(Map.of(matching.id(), matching));
        RecordingIndex index = new RecordingIndex(List.of(new SkillSearchHit(matching.id(), 100, List.of("id"))));
        SkillCatalogService catalog = catalog(repository, source, index, mock(SkillAuthorizationService.class));

        PageResult<SkillSummary> result = catalog.list(new SkillQuery("release-notes", "", "", "", 1, 12), actor());

        assertThat(result.items()).extracting(SkillSummary::id).containsExactly("release-notes");
        assertThat(result.items().getFirst().search()).isEqualTo(new SkillSearchMetadata(100, List.of("id")));
        assertThat(repository.publishedCalls.get()).isZero();
        assertThat(source.findRecordIds).containsExactly("release-notes");
        assertThat(index.queries).containsExactly(new SkillSearchQuery("release-notes", "", "", "", "updated"));
    }

    @Test
    void omitsHiddenCandidatesBeforeCalculatingTotalAndPagination() {
        SkillRecord hidden = skill("hidden", "Hidden");
        SkillRecord firstVisible = skill("visible-a", "Visible A");
        SkillRecord secondVisible = skill("visible-b", "Visible B");
        RecordingSource source = new RecordingSource(Map.of(hidden.id(), hidden, firstVisible.id(), firstVisible,
                secondVisible.id(), secondVisible));
        RecordingIndex index = new RecordingIndex(List.of(
                new SkillSearchHit(hidden.id(), 3, List.of("name")),
                new SkillSearchHit(firstVisible.id(), 2, List.of("name")),
                new SkillSearchHit(secondVisible.id(), 1, List.of("name"))));
        SkillAuthorizationService authorization = mock(SkillAuthorizationService.class);
        doThrow(new SkillNotVisibleException()).when(authorization).requireVisible(eq(hidden.id()), any(), any());
        SkillCatalogService catalog = catalog(new RecordingRepository(), source, index, authorization);

        PageResult<SkillSummary> result = catalog.list(new SkillQuery("visible", "", "", "", 2, 1), actor());

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(SkillSummary::id).containsExactly("visible-b");
        verify(authorization).requireVisible(eq(hidden.id()), eq(actor()), any());
        verify(authorization).requireVisible(eq(firstVisible.id()), eq(actor()), any());
        verify(authorization).requireVisible(eq(secondVisible.id()), eq(actor()), any());
    }

    @Test
    void omitsWithdrawnCandidatesEvenWhenTheSourceResolvesThem() {
        SkillRecord withdrawn = record("withdrawn", "Withdrawn", "withdrawn", "2026-08-01");
        RecordingSource source = new RecordingSource(Map.of(withdrawn.id(), withdrawn));
        SkillCatalogService catalog = catalog(new RecordingRepository(), source,
                new RecordingIndex(List.of(new SkillSearchHit(withdrawn.id(), 1, List.of("name")))),
                mock(SkillAuthorizationService.class));

        PageResult<SkillSummary> result = catalog.list(new SkillQuery("withdrawn", "", "", "", 1, 12), actor());

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void omitsSourceRecordWhoseVersionDoesNotMatchTheCurrentGovernanceVersion() {
        SkillRecord stale = record("versioned", "Versioned", "1.0.0", "published", "2026-08-01");
        RecordingSource source = new RecordingSource(Map.of(stale.id(), stale));
        SkillCatalogService catalog = catalog(new RecordingRepository(), source,
                new RecordingIndex(List.of(new SkillSearchHit(stale.id(), 1, List.of("name")))),
                mock(SkillAuthorizationService.class), governance(version("versioned", "2.0.0", "published")));

        PageResult<SkillSummary> result = catalog.list(new SkillQuery("versioned", "", "", "", 1, 12), actor());

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void omitsCandidateWhenTheCurrentGovernanceVersionIsWithdrawn() {
        SkillRecord staleIndexRecord = record("withdrawn-current", "Withdrawn current", "2.0.0", "published", "2026-08-01");
        RecordingSource source = new RecordingSource(Map.of(staleIndexRecord.id(), staleIndexRecord));
        SkillCatalogService catalog = catalog(new RecordingRepository(), source,
                new RecordingIndex(List.of(new SkillSearchHit(staleIndexRecord.id(), 1, List.of("name")))),
                mock(SkillAuthorizationService.class), governance(version("withdrawn-current", "2.0.0", "withdrawn")));

        PageResult<SkillSummary> result = catalog.list(new SkillQuery("withdrawn", "", "", "", 1, 12), actor());

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void omitsSourceRecordWhoseStatusDoesNotMatchTheCurrentGovernanceVersion() {
        SkillRecord staleStatus = record("status-current", "Status current", "2.0.0", "published", "2026-08-01");
        RecordingSource source = new RecordingSource(Map.of(staleStatus.id(), staleStatus));
        SkillCatalogService catalog = catalog(new RecordingRepository(), source,
                new RecordingIndex(List.of(new SkillSearchHit(staleStatus.id(), 1, List.of("name")))),
                mock(SkillAuthorizationService.class), governance(version("status-current", "2.0.0", "deprecated")));

        PageResult<SkillSummary> result = catalog.list(new SkillQuery("status", "", "", "", 1, 12), actor());

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void includesSearchMetadataOnlyForTextQueries() {
        SkillRecord matching = skill("matching", "Matching");
        RecordingSource source = new RecordingSource(Map.of(matching.id(), matching));
        RecordingIndex index = new RecordingIndex(List.of(new SkillSearchHit(matching.id(), 42, List.of("name", "tags"))));
        SkillCatalogService catalog = catalog(new RecordingRepository(), source, index, mock(SkillAuthorizationService.class));

        SkillSummary withText = catalog.list(new SkillQuery("matching", "", "", "", 1, 12), actor()).items().getFirst();
        SkillSummary filtered = catalog.list(new SkillQuery("", "other", "", "", 1, 12), actor()).items().getFirst();

        assertThat(withText.search()).isEqualTo(new SkillSearchMetadata(42, List.of("name", "tags")));
        assertThat(filtered.search()).isNull();
    }

    @Test
    void ordersByIndexRelevanceOnlyWhenRelevanceSortIsRequested() {
        SkillRecord lowerScore = record("lower", "Lower", "published", "2026-08-01");
        SkillRecord higherScore = record("higher", "Higher", "published", "2026-08-02");
        RecordingSource source = new RecordingSource(Map.of(lowerScore.id(), lowerScore, higherScore.id(), higherScore));
        RecordingIndex index = new RecordingIndex(List.of(
                new SkillSearchHit(lowerScore.id(), 1, List.of("name")),
                new SkillSearchHit(higherScore.id(), 2, List.of("name"))));
        SkillCatalogService catalog = catalog(new RecordingRepository(), source, index, mock(SkillAuthorizationService.class));

        PageResult<SkillSummary> result = catalog.list(new SkillQuery("skill", "", "", "", 1, 12, "relevance"), actor());

        assertThat(result.items()).extracting(SkillSummary::id).containsExactly("lower", "higher");
    }

    private SkillCatalogService catalog(RecordingRepository repository, RecordingSource source, RecordingIndex index,
                                        SkillAuthorizationService authorization) {
        return catalog(repository, source, index, authorization,
                new GovernanceStore(tempDir.resolve("state.json"), List.copyOf(source.records.values())));
    }

    private SkillCatalogService catalog(RecordingRepository repository, RecordingSource source, RecordingIndex index,
                                        SkillAuthorizationService authorization, GovernanceStore governanceStore) {
        return new SkillCatalogService(repository, governanceStore,
                new ObjectMapper().findAndRegisterModules(), authorization, null, index, source,
                new SkillSearchRefreshCoordinator(index, source));
    }

    private static Actor actor() {
        return new Actor("viewer", "viewer");
    }

    private static SkillRecord skill(String id, String name) {
        return record(id, name, "published", "2026-08-01");
    }

    private static SkillRecord record(String id, String name, String status, String updated) {
        return record(id, name, "1.0.0", status, updated);
    }

    private static SkillRecord record(String id, String name, String version, String status, String updated) {
        return new SkillRecord(id, name, version, "description", "other", List.of("tag"), "low", "low",
                "team", "owner", "", "blue", status, updated, updated, "Java", "Java", "none",
                List.of(), List.of(), List.of(), "", "", "", List.of(), new SkillMetrics(0, 0, 0, 0, 0));
    }

    private static GovernanceStore governance(SkillVersion version) {
        GovernanceStore store = mock(GovernanceStore.class);
        when(store.snapshot()).thenReturn(new GovernanceSnapshot(List.of(version), List.of(), List.of(), List.of()));
        return store;
    }

    private static SkillVersion version(String skillId, String version, String status) {
        Instant timestamp = Instant.parse("2026-08-01T00:00:00Z");
        return new SkillVersion("package-" + skillId, skillId, version, status, "0".repeat(64), 0, "", "owner",
                timestamp, "owner", timestamp, "review-" + skillId);
    }

    private static final class RecordingRepository implements SkillRepository {
        private final AtomicInteger publishedCalls = new AtomicInteger();

        @Override
        public PageResult<SkillRecord> findPublished(SkillQuery query) {
            publishedCalls.incrementAndGet();
            throw new AssertionError("indexed catalog path must not read repository pages");
        }

        @Override
        public Optional<SkillRecord> findDetail(String skillId) {
            return Optional.empty();
        }
    }

    private static final class RecordingSource implements SkillSearchDocumentSource {
        private final Map<String, SkillRecord> records;
        private final List<String> findRecordIds = new ArrayList<>();

        private RecordingSource(Map<String, SkillRecord> records) {
            this.records = new LinkedHashMap<>(records);
        }

        @Override
        public SkillSearchDocumentSnapshot snapshot() {
            return new SkillSearchDocumentSnapshot(List.of(), "test-source", 1);
        }

        @Override
        public Optional<SkillRecord> findRecord(String skillId) {
            findRecordIds.add(skillId);
            return Optional.ofNullable(records.get(skillId));
        }
    }

    private static final class RecordingIndex implements SkillSearchIndex {
        private final List<SkillSearchHit> hits;
        private final List<SkillSearchQuery> queries = new ArrayList<>();
        private String sourceHash = "";

        private RecordingIndex(List<SkillSearchHit> hits) {
            this.hits = List.copyOf(hits);
        }

        @Override
        public SkillSearchIndexStatus status() {
            return new SkillSearchIndexStatus(sourceHash.isEmpty() ? "NOT_READY" : "READY", 1, hits.size(), sourceHash, "1");
        }

        @Override
        public SkillSearchRebuildResult rebuild(List<SkillSearchDocument> documents, String sourceHash) {
            this.sourceHash = sourceHash;
            return new SkillSearchRebuildResult(1, documents.size(), sourceHash, "1");
        }

        @Override
        public void invalidate(String reason) {
            sourceHash = "";
        }

        @Override
        public List<SkillSearchHit> search(SkillSearchQuery query) {
            queries.add(query);
            return hits;
        }
    }
}
