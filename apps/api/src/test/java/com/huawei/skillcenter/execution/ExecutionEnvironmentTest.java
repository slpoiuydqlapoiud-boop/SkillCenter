package com.huawei.skillcenter.execution;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionEnvironmentTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void acceptsVersionedNonSecretEnvironmentAsset() {
        ExecutionEnvironment environment = new ExecutionEnvironment(
                "openclaw", ExecutionEnvironmentKind.AGENT_RUNTIME, "context-v1",
                ExecutionEnvironmentStatus.ACTIVE, List.of("context-only", "trace"),
                "openclaw-runner", "secret://skillcenter/openclaw",
                "admin", CREATED_AT, "admin", CREATED_AT);

        assertThat(environment.businessKey()).isEqualTo("AGENT_RUNTIME/openclaw");
        assertThat(environment.capabilities()).containsExactly("context-only", "trace");
        assertThat(environment.configReference()).isEqualTo("secret://skillcenter/openclaw");
    }

    @Test
    void rejectsUnboundedIdentifiersAndRawCredentialReferences() {
        assertThatThrownBy(() -> new ExecutionEnvironment(
                "customer prompt", ExecutionEnvironmentKind.AGENT_RUNTIME, "context-v1",
                ExecutionEnvironmentStatus.ACTIVE, List.of(), "runner", "", "admin", CREATED_AT, "admin", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environmentId");
        assertThatThrownBy(() -> new ExecutionEnvironment(
                "openclaw", ExecutionEnvironmentKind.AGENT_RUNTIME, "context-v1",
                ExecutionEnvironmentStatus.ACTIVE, List.of(), "runner", "Bearer secret", "admin", CREATED_AT, "admin", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configReference");
    }

    @Test
    void rejectsInvalidLifecycleAndOversizedCapabilities() {
        assertThatThrownBy(() -> ExecutionEnvironmentStatus.from("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> new ExecutionEnvironment(
                "openclaw", ExecutionEnvironmentKind.AGENT_RUNTIME, "",
                ExecutionEnvironmentStatus.ACTIVE, List.of(), "runner", "", "admin", CREATED_AT, "admin", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> new ExecutionEnvironment(
                "openclaw", ExecutionEnvironmentKind.AGENT_RUNTIME, "context-v1",
                ExecutionEnvironmentStatus.ACTIVE, java.util.stream.IntStream.range(0, 21).mapToObj(index -> "cap-" + index).toList(),
                "runner", "", "admin", CREATED_AT, "admin", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capabilities");
    }
}
