package com.huawei.skillcenter.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;

class SkillSearchRefreshEventDeliveryTest {
    @Test
    void eventKeyIsDeterministicWithoutAddingSensitivePayload() {
        SkillSearchRefreshEvent first = new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_PUBLISHED");
        SkillSearchRefreshEvent duplicate = new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_PUBLISHED");

        assertThat(first.eventKey()).isEqualTo(duplicate.eventKey());
        assertThat(first.eventKey()).isEqualTo("skill-a|7|VERSION_PUBLISHED");
        assertThat(SkillSearchRefreshEvent.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("skillId", "sourceRevision", "reasonCode");
    }

    @Test
    void coordinatorIgnoresDuplicateRefreshEvents() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        SkillSearchRefreshCoordinator coordinator = new SkillSearchRefreshCoordinator(index, new TestSource());
        SkillSearchRefreshEvent event = new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_PUBLISHED");

        coordinator.onRefresh(event);
        coordinator.onRefresh(event);

        assertThat(index.status().state()).isEqualTo("STALE");
        assertThat(coordinator.appliedRefreshEventCount()).isEqualTo(1);
    }

    @Test
    void pollerAdvancesOnlyAfterSuccessfulDelivery() {
        AtomicInteger deliveries = new AtomicInteger();
        SkillSearchRefreshEventStore store = new InMemoryEventStore();
        SkillSearchRefreshEventPoller poller = new SkillSearchRefreshEventPoller(store,
                event -> {
                    if (deliveries.incrementAndGet() == 1) {
                        throw new IllegalStateException("temporary");
                    }
                }, 10);

        poller.runOnce();
        assertThat(poller.cursor()).isZero();
        poller.runOnce();
        assertThat(poller.cursor()).isEqualTo(1L);
    }

    @Test
    void journalDoesNotBreakSuccessfulGovernanceEventPublicationWhenStoreIsUnavailable() {
        SkillSearchRefreshEventStore store = mock(SkillSearchRefreshEventStore.class);
        doThrow(new IllegalStateException("temporary persistence failure"))
                .when(store).append(org.mockito.ArgumentMatchers.any());
        SkillSearchRefreshEventJournal journal = new SkillSearchRefreshEventJournal(store);

        assertThatCode(() -> journal.append(new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_PUBLISHED")))
                .doesNotThrowAnyException();
    }

    @Test
    void migrationCreatesReplayableRefreshJournal() throws Exception {
        String migration = new org.springframework.core.io.ClassPathResource(
                "db/migration/V17__create_skill_search_refresh_events.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_search_refresh_events")
                .contains("PRIMARY KEY (event_id)")
                .contains("UNIQUE (event_seq)")
                .contains("source_revision");
    }

    private static final class InMemoryEventStore implements SkillSearchRefreshEventStore {
        @Override
        public void append(SkillSearchRefreshEvent event) {
        }

        @Override
        public List<StoredSkillSearchRefreshEvent> findAfter(long sequence, int limit) {
            return sequence == 0
                    ? List.of(new StoredSkillSearchRefreshEvent(1L,
                    new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_PUBLISHED")))
                    : List.of();
        }
    }

    private static final class TestSource implements SkillSearchDocumentSource {
        @Override
        public SkillSearchDocumentSnapshot snapshot() {
            return new SkillSearchDocumentSnapshot(List.of(), "source", 7L);
        }

        @Override
        public java.util.Optional<com.huawei.skillcenter.skill.SkillRecord> findRecord(String skillId) {
            return java.util.Optional.empty();
        }
    }
}
