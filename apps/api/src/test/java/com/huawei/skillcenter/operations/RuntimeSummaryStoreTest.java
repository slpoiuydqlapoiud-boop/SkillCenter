package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeSummaryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsSummariesReloadsThemAndDeduplicatesSameEvent() {
        Path path = tempDir.resolve("runtime-summaries.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RuntimeSummary event = summary(OffsetDateTime.now(ZoneOffset.UTC));
        RuntimeSummaryStore first = new RuntimeSummaryStore(path, mapper);

        assertThat(first.putIfAbsent(event)).isFalse();
        assertThat(first.putIfAbsent(event)).isTrue();
        RuntimeSummaryStore reloaded = new RuntimeSummaryStore(path, mapper);

        assertThat(reloaded.findAll()).containsExactly(event);
    }

    @Test
    void rejectsConflictingEventAndSupportsRetentionDeletion() {
        Path path = tempDir.resolve("runtime-summaries.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RuntimeSummaryStore store = new RuntimeSummaryStore(path, mapper);
        RuntimeSummary oldEvent = summary(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
        store.putIfAbsent(oldEvent);
        RuntimeSummary conflict = new RuntimeSummary(oldEvent.schemaVersion(), oldEvent.eventId(), oldEvent.occurredAt(),
                oldEvent.skillId(), oldEvent.version(), "success", oldEvent.durationMs(), null,
                oldEvent.dataSource(), oldEvent.teamId(), oldEvent.clientType(), oldEvent.traceRef());

        assertThatThrownBy(() -> store.putIfAbsent(conflict))
                .isInstanceOf(RuntimeSummaryConflictException.class);
        assertThat(store.countBefore(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toInstant())).isEqualTo(1);
        assertThat(store.deleteBefore(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toInstant())).isEqualTo(1);
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void rejectsSemanticallyInvalidPersistedEntriesDuringStartup() throws Exception {
        Path path = tempDir.resolve("runtime-summaries.json");
        Files.writeString(path, "[{\"schemaVersion\":\"1.0\"}]");

        assertThatThrownBy(() -> new RuntimeSummaryStore(path, new ObjectMapper().findAndRegisterModules()))
                .isInstanceOf(RuntimeSummaryStore.RuntimeSummaryPersistenceException.class);
    }

    @Test
    void rejectsDuplicateEventIdsDuringStartup() throws Exception {
        RuntimeSummary event = summary(OffsetDateTime.now(ZoneOffset.UTC));
        Path path = tempDir.resolve("runtime-summaries.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Files.writeString(path, mapper.writeValueAsString(java.util.List.of(event, event)));

        assertThatThrownBy(() -> new RuntimeSummaryStore(path, mapper))
                .isInstanceOf(RuntimeSummaryStore.RuntimeSummaryPersistenceException.class);
    }

    @Test
    void rejectsUnknownDataSourceBeforeWritingRuntimeSummary() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RuntimeSummaryStore store = new RuntimeSummaryStore(tempDir.resolve("invalid-source.json"), mapper);
        RuntimeSummary invalid = new RuntimeSummary("1.0", UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC),
                "skill-a", "1.0.0", "success", 10, "", "experimental", null, "codex", "trace-a");

        assertThatThrownBy(() -> store.putIfAbsent(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataSource");
    }

    private RuntimeSummary summary(OffsetDateTime occurredAt) {
        return new RuntimeSummary("1.0", UUID.randomUUID(), occurredAt, "skill-a", "1.0.0", "failure",
                80, "E_DEP", "production", "team-a", "codex", "trace-a");
    }
}
