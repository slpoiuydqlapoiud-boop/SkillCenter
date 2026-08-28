package com.huawei.skillcenter.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
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
    void pollerRestoresAndPersistsCursorForItsConsumerIdentity() {
        CursorEventStore store = new CursorEventStore();
        SkillSearchRefreshEventPoller poller = new SkillSearchRefreshEventPoller(store,
                event -> { }, "api-1", 10);

        poller.runOnce();

        assertThat(store.loadedConsumerId).isEqualTo("api-1");
        assertThat(store.savedConsumerId).isEqualTo("api-1");
        assertThat(store.savedSequence).isEqualTo(5L);
        assertThat(poller.cursor()).isEqualTo(5L);
    }

    @Test
    void pollerDoesNotResurrectRetiredConsumer() {
        LifecycleEventStore store = new LifecycleEventStore();
        SkillSearchRefreshEventPoller poller = new SkillSearchRefreshEventPoller(store,
                event -> { }, "api-retired", 10);

        poller.runOnce();

        assertThat(store.registerCalls).isEqualTo(1);
        assertThat(store.findAfterCalls).isZero();
        assertThat(poller.cursor()).isZero();
    }

    @Test
    void pollerDoesNotJumpItsLocalCursorWhenSharedCursorAdvances() {
        AdvancingCursorEventStore store = new AdvancingCursorEventStore();
        SkillSearchRefreshEventPoller poller = new SkillSearchRefreshEventPoller(store,
                event -> { }, "api-1", 10);

        poller.runOnce();
        poller.runOnce();

        assertThat(store.lastFindAfterSequence).isEqualTo(1L);
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

        String cursorMigration = new org.springframework.core.io.ClassPathResource(
                "db/migration/V18__create_skill_search_refresh_event_consumers.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(cursorMigration)
                .contains("CREATE TABLE skill_search_refresh_event_consumers")
                .contains("PRIMARY KEY (consumer_id)")
                .contains("last_event_seq");

        String lifecycleMigration = new org.springframework.core.io.ClassPathResource(
                "db/migration/V20__add_skill_search_refresh_consumer_lifecycle.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(lifecycleMigration)
                .contains("status")
                .contains("last_seen_at")
                .contains("retired_at")
                .contains("retirement_consistent")
                .contains("skill_search_refresh_event_consumers_lifecycle");

        String retentionMigration = new org.springframework.core.io.ClassPathResource(
                "db/migration/V19__index_skill_search_refresh_event_retention.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(retentionMigration)
                .contains("skill_search_refresh_events_created_at_sequence")
                .contains("created_at, event_seq");
    }

    private static final class CursorEventStore implements SkillSearchRefreshEventStore {
        private String loadedConsumerId;
        private String savedConsumerId;
        private long savedSequence;

        @Override
        public void append(SkillSearchRefreshEvent event) {
        }

        @Override
        public List<StoredSkillSearchRefreshEvent> findAfter(long sequence, int limit) {
            return sequence == 4L
                    ? List.of(new StoredSkillSearchRefreshEvent(5L,
                    new SkillSearchRefreshEvent("skill-a", 8L, "VERSION_WITHDRAWN")))
                    : List.of();
        }

        @Override
        public long loadCursor(String consumerId) {
            loadedConsumerId = consumerId;
            return 4L;
        }

        @Override
        public void saveCursor(String consumerId, long sequence) {
            savedConsumerId = consumerId;
            savedSequence = sequence;
        }
    }

    private static final class LifecycleEventStore implements SkillSearchRefreshEventStore {
        private int registerCalls;
        private int findAfterCalls;

        @Override
        public void append(SkillSearchRefreshEvent event) {
        }

        @Override
        public List<StoredSkillSearchRefreshEvent> findAfter(long sequence, int limit) {
            findAfterCalls++;
            return List.of();
        }

        @Override
        public SkillSearchRefreshConsumerState registerConsumer(String consumerId, Instant now) {
            registerCalls++;
            return new SkillSearchRefreshConsumerState(consumerId, 0L,
                    SkillSearchRefreshConsumerStatus.RETIRED, now, now);
        }

        @Override
        public SkillSearchRefreshConsumerState heartbeat(String consumerId, Instant now) {
            return new SkillSearchRefreshConsumerState(consumerId, 0L,
                    SkillSearchRefreshConsumerStatus.RETIRED, now, now);
        }
    }

    private static final class AdvancingCursorEventStore implements SkillSearchRefreshEventStore {
        private long lastFindAfterSequence = -1L;

        @Override
        public void append(SkillSearchRefreshEvent event) {
        }

        @Override
        public List<StoredSkillSearchRefreshEvent> findAfter(long sequence, int limit) {
            lastFindAfterSequence = sequence;
            return sequence == 0L
                    ? List.of(new StoredSkillSearchRefreshEvent(1L,
                    new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_PUBLISHED")))
                    : List.of();
        }

        @Override
        public SkillSearchRefreshConsumerState heartbeat(String consumerId, Instant now) {
            return new SkillSearchRefreshConsumerState(consumerId, 5L,
                    SkillSearchRefreshConsumerStatus.ACTIVE, now, null);
        }
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
