package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSummaryRepositoryContractTest {
    @Test
    void serviceCanUseAnyRepositoryWithoutChangingIdempotencySemantics() {
        RecordingRepository repository = new RecordingRepository();
        RuntimeSummaryService service = new RuntimeSummaryService(repository);
        RuntimeSummary event = new RuntimeSummary("1.0", UUID.randomUUID(),
                OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.UTC),
                "skill-a", "1.0.0", "success", 50, null, "mock", "team-a", "codex", "trace-a");

        assertThat(service.ingest(event).duplicate()).isFalse();
        assertThat(service.ingest(event).duplicate()).isTrue();
        assertThat(service.events()).containsExactly(event);
        assertThat(repository.putCalls).isEqualTo(2);
    }

    private static final class RecordingRepository implements RuntimeSummaryRepository {
        private final List<RuntimeSummary> events = new ArrayList<>();
        private int putCalls;

        @Override
        public boolean putIfAbsent(RuntimeSummary summary) {
            putCalls++;
            if (events.stream().anyMatch(item -> item.eventId().equals(summary.eventId()))) return true;
            events.add(summary);
            return false;
        }

        @Override
        public List<RuntimeSummary> findAll() {
            return List.copyOf(events);
        }

        @Override
        public long countBefore(java.time.Instant cutoff) {
            return events.stream().filter(item -> item.occurredAt().toInstant().isBefore(cutoff)).count();
        }

        @Override
        public int deleteBefore(java.time.Instant cutoff) {
            int before = events.size();
            events.removeIf(item -> item.occurredAt().toInstant().isBefore(cutoff));
            return before - events.size();
        }

        @Override
        public void clear() {
            events.clear();
        }
    }
}
