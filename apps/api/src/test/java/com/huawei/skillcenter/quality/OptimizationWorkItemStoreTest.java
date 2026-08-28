package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationWorkItemStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsWorkItemWithEvidenceContext() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path state = tempDir.resolve("optimization-work-items.json");
        OptimizationWorkItem item = workItem("work-1", "OPEN", "");

        new OptimizationWorkItemStore(state, mapper).create(item);

        OptimizationWorkItemStore reloaded = new OptimizationWorkItemStore(state, mapper);
        assertThat(reloaded.find("work-1")).contains(item);
        assertThat(reloaded.findAll("skill-a", null, null, null)).containsExactly(item);
    }

    @Test
    void rejectsDuplicateIdsAndDuplicateOpenBusinessKeys() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OptimizationWorkItemStore store = new OptimizationWorkItemStore(
                tempDir.resolve("optimization-work-items-duplicates.json"), mapper);
        OptimizationWorkItem first = workItem("work-1", "OPEN", "");
        store.create(first);

        assertThatThrownBy(() -> store.create(first))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workItemId");
        assertThatThrownBy(() -> store.create(workItem("work-2", "PLANNED", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active work item");
    }

    @Test
    void permitsAnotherWorkItemAfterPreviousOneIsTerminal() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OptimizationWorkItemStore store = new OptimizationWorkItemStore(
                tempDir.resolve("optimization-work-items-terminal.json"), mapper);
        store.create(workItem("work-1", "COMPLETED", "benchmark-1"));

        assertThat(store.create(workItem("work-2", "OPEN", ""))).isNotNull();
    }

    @Test
    void rejectsPersistedDuplicateIds() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path state = tempDir.resolve("optimization-work-items-invalid.json");
        OptimizationWorkItem item = workItem("work-1", "OPEN", "");
        mapper.writeValue(state.toFile(), List.of(item, item));

        assertThatThrownBy(() -> new OptimizationWorkItemStore(state, mapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to read optimization work items");
    }

    @Test
    void rejectsPersistedPartialSuiteContext() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path state = tempDir.resolve("optimization-work-items-partial-suite.json");
        ObjectNode partial = mapper.valueToTree(workItem("work-partial", "OPEN", ""));
        partial.put("suiteId", "suite-a");
        partial.put("suiteVersion", "");
        mapper.writeValue(state.toFile(), List.of(partial));

        assertThatThrownBy(() -> new OptimizationWorkItemStore(state, mapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to read optimization work items");
    }

    private OptimizationWorkItem workItem(String id, String status, String evidenceId) {
        return new OptimizationWorkItem(id, "skill-a", "1.0.0", "runtime-latency",
                "运行 P95 延迟偏高", "LATENCY", "MEDIUM", List.of("p95Ms=1200"),
                "降低运行延迟", "admin", status, status.equals("COMPLETED") ? "1.1.0" : "",
                status.equals("COMPLETED") ? "BENCHMARK" : "NONE", evidenceId,
                status.equals("COMPLETED") ? "已验证" : "", "production", "runtime-a",
                "mcp-a", "llm-a", "admin", Instant.parse("2026-08-24T01:02:03Z"),
                "admin", Instant.parse("2026-08-24T01:02:03Z"));
    }
}
