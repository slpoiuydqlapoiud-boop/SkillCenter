package com.huawei.skillcenter.search;

import com.huawei.skillcenter.skill.SkillMetrics;
import com.huawei.skillcenter.skill.SkillRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SkillSearchRefreshCoordinatorTest {
    @Test
    void ensureReadyPerformsOnlyOneInitialRebuild() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        CountingSource source = new CountingSource(snapshot("source-a"));
        SkillSearchRefreshCoordinator coordinator = new SkillSearchRefreshCoordinator(index, source);

        coordinator.ensureReady();
        coordinator.ensureReady();

        assertThat(source.snapshotCalls).isEqualTo(1);
        assertThat(index.status()).extracting(SkillSearchIndexStatus::state, SkillSearchIndexStatus::sourceHash)
                .containsExactly("READY", "source-a");
    }

    @Test
    void rebuildRejectsExpectedHashConflictBeforeChangingCommittedHitsAndInvalidationPreservesThem() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        CountingSource source = new CountingSource(snapshot("source-a"));
        SkillSearchRefreshCoordinator coordinator = new SkillSearchRefreshCoordinator(index, source);
        coordinator.ensureReady();

        SkillSearchRebuildResult conflict = coordinator.rebuild("different-source", "actor", "request");
        coordinator.invalidate(new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_DEPRECATED"));

        assertThat(conflict.reasonCode()).isEqualTo("SEARCH_INDEX_SOURCE_CONFLICT");
        assertThat(index.search(SkillSearchQuery.of("skill-a", "", "", "", "relevance"))).hasSize(1);
        assertThat(coordinator.status().state()).isEqualTo("STALE");
    }

    @Test
    void refreshContractsContainOnlyBoundedMetadata() {
        SkillSearchRefreshEvent event = new SkillSearchRefreshEvent("skill-a", 4L, "VERSION_PUBLISHED");

        assertThat(event).extracting(SkillSearchRefreshEvent::skillId, SkillSearchRefreshEvent::sourceRevision,
                        SkillSearchRefreshEvent::reasonCode)
                .containsExactly("skill-a", 4L, "VERSION_PUBLISHED");
        assertThat(SkillSearchRefreshEvent.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("skillId", "sourceRevision", "reasonCode");
        assertThat(SkillSearchIndexStatus.class.getRecordComponents()).extracting(component -> component.getName())
                .doesNotContain("prompt", "input", "output", "token", "trace", "exception");
    }

    private static SkillSearchDocumentSnapshot snapshot(String sourceHash) {
        return new SkillSearchDocumentSnapshot(List.of(new SkillSearchDocument("skill-a", "Skill A", "safe", List.of("tag"),
                "team", "tools", "published", "low", Instant.parse("2026-01-02T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), "1.0.0", "PUBLIC", "")), sourceHash, 7L);
    }

    private static final class CountingSource implements SkillSearchDocumentSource {
        private final SkillSearchDocumentSnapshot snapshot;
        private int snapshotCalls;

        private CountingSource(SkillSearchDocumentSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public SkillSearchDocumentSnapshot snapshot() {
            snapshotCalls++;
            return snapshot;
        }

        @Override
        public Optional<SkillRecord> findRecord(String skillId) {
            return Optional.empty();
        }
    }
}
