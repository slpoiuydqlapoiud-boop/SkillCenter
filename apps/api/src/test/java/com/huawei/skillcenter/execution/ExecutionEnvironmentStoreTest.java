package com.huawei.skillcenter.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionEnvironmentStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void seedsStableContextAssetsAndRestoresCreatedAssets() {
        Path state = tempDir.resolve("execution-environments.json");
        ExecutionEnvironmentStore store = store(state);

        assertThat(store.findAll(null, null)).extracting(ExecutionEnvironment::businessKey)
                .containsExactly("AGENT_RUNTIME/openclaw", "LLM_PROVIDER/llm-gateway", "MCP_SERVER/mcp-network");
        ExecutionEnvironment created = environment("custom-runtime", ExecutionEnvironmentKind.AGENT_RUNTIME);
        store.create(created);

        ExecutionEnvironmentStore restarted = store(state);
        assertThat(restarted.find(ExecutionEnvironmentKind.AGENT_RUNTIME, "custom-runtime")).contains(created);
        assertThat(restarted.findAll(ExecutionEnvironmentKind.AGENT_RUNTIME, ExecutionEnvironmentStatus.ACTIVE))
                .extracting(ExecutionEnvironment::environmentId)
                .containsExactly("custom-runtime", "openclaw");
    }

    @Test
    void rejectsDuplicateBusinessKeyAndPersistsStatusReplacement() {
        Path state = tempDir.resolve("status.json");
        ExecutionEnvironmentStore store = store(state);
        ExecutionEnvironment duplicate = environment("openclaw", ExecutionEnvironmentKind.AGENT_RUNTIME);

        assertThatThrownBy(() -> store.create(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        ExecutionEnvironment degraded = store.find(ExecutionEnvironmentKind.AGENT_RUNTIME, "openclaw")
                .orElseThrow().withStatus(ExecutionEnvironmentStatus.DEGRADED, "admin", Instant.parse("2026-08-24T01:00:00Z"));
        store.replace(degraded);

        assertThat(store.find(ExecutionEnvironmentKind.AGENT_RUNTIME, "openclaw").orElseThrow().status())
                .isEqualTo(ExecutionEnvironmentStatus.DEGRADED);
    }

    @Test
    void rejectsMalformedSnapshotInsteadOfSilentlyStartingEmpty() throws Exception {
        Path state = tempDir.resolve("malformed.json");
        Files.writeString(state, "[{\"environmentId\":\"bad\",\"kind\":\"UNKNOWN\"}]");

        assertThatThrownBy(() -> store(state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to read execution environments");
    }

    private ExecutionEnvironmentStore store(Path state) {
        return new ExecutionEnvironmentStore(state, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
    }

    private ExecutionEnvironment environment(String id, ExecutionEnvironmentKind kind) {
        return new ExecutionEnvironment(id, kind, "context-v1", ExecutionEnvironmentStatus.ACTIVE,
                List.of("context-only"), "", "", "admin",
                Instant.parse("2026-08-24T00:00:00Z"), "admin", Instant.parse("2026-08-24T00:00:00Z"));
    }
}
