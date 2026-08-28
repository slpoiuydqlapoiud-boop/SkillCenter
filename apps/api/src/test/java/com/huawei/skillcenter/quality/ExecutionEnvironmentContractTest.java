package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionEnvironmentContractTest {
    @Test
    void carriesOnlyBoundedExecutionEnvironmentIdentifiers() {
        SkillExecutionRequest request = new SkillExecutionRequest(
                "eox-query", "1.2.0", "success", 1_000,
                "openclaw", "mcp-network", "llm-gateway");

        assertThat(request.runtimeId()).isEqualTo("openclaw");
        assertThat(request.mcpServerId()).isEqualTo("mcp-network");
        assertThat(request.llmProviderId()).isEqualTo("llm-gateway");

        RunnerExecutionRequest runnerRequest = new RunnerExecutionRequest(
                request.skillId(), request.skillVersion(), "run-1", "smoke", "case-1",
                request.timeoutMs(), request.scenario(), request.runtimeId(), request.mcpServerId(),
                request.llmProviderId());
        assertThat(runnerRequest.runtimeId()).isEqualTo("openclaw");
        assertThat(runnerRequest.mcpServerId()).isEqualTo("mcp-network");
        assertThat(runnerRequest.llmProviderId()).isEqualTo("llm-gateway");
    }

    @Test
    void rejectsExecutionEnvironmentValuesThatCouldCarryPayloads() {
        assertThatThrownBy(() -> new SkillExecutionRequest(
                "eox-query", "1.2.0", "success", 1_000,
                "openclaw", "customer prompt: secret", "llm-gateway"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcpServerId");
    }

    @Test
    void persistsEnvironmentContextOnEvaluationRunAndQualitySnapshot() {
        QualityEvaluationService service = new QualityEvaluationService(
                new MockRunner(), new MockEvaluationProvider(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));
        EvaluationRun queued = service.submit(new EvaluationRequest(
                "eox-query", "1.2.0", "smoke", "success", 1_000,
                "openclaw", "mcp-network", "llm-gateway"));
        EvaluationRun completed = awaitCompleted(service, queued.id());

        assertThat(completed.runtimeId()).isEqualTo("openclaw");
        assertThat(completed.mcpServerId()).isEqualTo("mcp-network");
        assertThat(completed.llmProviderId()).isEqualTo("llm-gateway");
        QualitySnapshot snapshot = service.snapshot(queued.id());
        assertThat(snapshot.runtimeId()).isEqualTo("openclaw");
        assertThat(snapshot.mcpServerId()).isEqualTo("mcp-network");
        assertThat(snapshot.llmProviderId()).isEqualTo("llm-gateway");
        assertThat(service.snapshots("eox-query", "openclaw", "mcp-network", "llm-gateway"))
                .extracting(QualitySnapshot::snapshotId).containsExactly(queued.id());
        assertThat(service.snapshots("eox-query", "other-runtime", "mcp-network", "llm-gateway"))
                .isEmpty();
    }

    private EvaluationRun awaitCompleted(QualityEvaluationService service, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        EvaluationRun current;
        do {
            current = service.find(id);
            if (current.status() == EvaluationRunStatus.COMPLETED) return current;
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        } while (System.nanoTime() < deadline);
        return current;
    }
}
