package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillExecutionStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void executionRecordsReloadAfterStoreRecreation() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UUID id = UUID.randomUUID();
        SkillExecutionRecord record = new SkillExecutionRecord(id, "eox-query", "1.2.0",
                RunnerExecutionStatus.SUCCEEDED, "mock-runner", "1.0", "mock", 42,
                "a".repeat(64), "", Instant.parse("2026-08-21T00:00:00Z"));

        new SkillExecutionStore(tempDir.resolve("executions.json"), mapper).save(record);
        SkillExecutionStore restored = new SkillExecutionStore(tempDir.resolve("executions.json"), mapper);

        assertThat(restored.find(id)).contains(record);
        assertThat(restored.findAll("eox-query")).containsExactly(record);
    }

    @Test
    void executionRecordsPersistRuntimeEnvironmentContext() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UUID id = UUID.randomUUID();
        SkillExecutionRecord record = new SkillExecutionRecord(id, "eox-query", "1.2.0",
                RunnerExecutionStatus.SUCCEEDED, "mock-runner", "1.0", "mock", 42,
                "a".repeat(64), "", Instant.parse("2026-08-21T00:00:00Z"),
                "runtime-a", "mcp-a", "llm-a");

        SkillExecutionStore store = new SkillExecutionStore(tempDir.resolve("executions-context.json"), mapper);
        store.save(record);

        assertThat(new SkillExecutionStore(tempDir.resolve("executions-context.json"), mapper).find(id))
                .get().extracting(SkillExecutionRecord::runtimeId, SkillExecutionRecord::mcpServerId,
                        SkillExecutionRecord::llmProviderId)
                .containsExactly("runtime-a", "mcp-a", "llm-a");
    }

    @Test
    void conflictingExecutionIdCannotSilentlyOverwriteEvidence() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UUID id = UUID.randomUUID();
        SkillExecutionRecord original = new SkillExecutionRecord(id, "eox-query", "1.2.0",
                RunnerExecutionStatus.SUCCEEDED, "mock-runner", "1.0", "mock", 42,
                "a".repeat(64), "", Instant.parse("2026-08-21T00:00:00Z"));
        SkillExecutionRecord conflicting = new SkillExecutionRecord(id, "eox-query", "1.2.0",
                RunnerExecutionStatus.FAILED, "mock-runner", "1.0", "mock", 55,
                "b".repeat(64), "RUNNER_FAILED", Instant.parse("2026-08-21T00:01:00Z"));
        SkillExecutionStore store = new SkillExecutionStore(tempDir.resolve("conflict.json"), mapper);
        store.save(original);

        assertThatThrownBy(() -> store.save(conflicting))
                .isInstanceOf(SkillExecutionStore.SkillExecutionConflictException.class);
        assertThat(store.find(id)).contains(original);
    }

    @Test
    void duplicateExecutionIdsAreRejectedDuringRecovery() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UUID id = UUID.randomUUID();
        SkillExecutionRecord record = new SkillExecutionRecord(id, "eox-query", "1.2.0",
                RunnerExecutionStatus.SUCCEEDED, "mock-runner", "1.0", "mock", 42,
                "a".repeat(64), "", Instant.parse("2026-08-21T00:00:00Z"));
        mapper.writeValue(tempDir.resolve("duplicates.json").toFile(), java.util.List.of(record, record));

        assertThatThrownBy(() -> new SkillExecutionStore(tempDir.resolve("duplicates.json"), mapper))
                .isInstanceOf(SkillExecutionStore.SkillExecutionPersistenceException.class);
    }

    @Test
    void executionHistoryFiltersByDataSourceAndEnvironment() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SkillExecutionStore store = new SkillExecutionStore(tempDir.resolve("filters.json"), mapper);
        store.save(new SkillExecutionRecord(UUID.randomUUID(), "skill-a", "1.0.0",
                RunnerExecutionStatus.SUCCEEDED, "mock-runner", "1.0", "mock", 42,
                "a".repeat(64), "", Instant.parse("2026-08-21T00:00:00Z"), "openclaw", "mcp-a", "llm-a"));
        store.save(new SkillExecutionRecord(UUID.randomUUID(), "skill-a", "1.0.0",
                RunnerExecutionStatus.SUCCEEDED, "openclaw", "1.0", "production", 42,
                "b".repeat(64), "", Instant.parse("2026-08-21T00:01:00Z"), "openclaw", "mcp-a", "llm-a"));

        assertThat(store.findAll("skill-a", "production", "openclaw", "mcp-a", "llm-a"))
                .extracting(SkillExecutionRecord::dataSource).containsExactly("production");
    }
}
