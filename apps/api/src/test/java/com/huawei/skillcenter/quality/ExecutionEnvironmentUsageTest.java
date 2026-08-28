package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.execution.ExecutionEnvironmentKind;
import com.huawei.skillcenter.execution.ExecutionEnvironmentService;
import com.huawei.skillcenter.execution.ExecutionEnvironmentStatus;
import com.huawei.skillcenter.execution.ExecutionEnvironmentStatusRequest;
import com.huawei.skillcenter.execution.ExecutionEnvironmentStore;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionEnvironmentUsageTest {
    @TempDir
    Path tempDir;

    @Test
    void newEvaluationRequiresActiveRegisteredEnvironmentButKeepsEmptyContextCompatible() {
        GovernanceStore governance = new GovernanceStore(tempDir.resolve("governance.json"), java.util.List.of());
        ExecutionEnvironmentStore store = new ExecutionEnvironmentStore(tempDir.resolve("environments.json"),
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
        ExecutionEnvironmentService environments = new ExecutionEnvironmentService(store, governance,
                Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
        QualityEvaluationService evaluations = new QualityEvaluationService(new MockRunner(), new MockEvaluationProvider(),
                Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC), new QualityEvidenceStore(), environments);

        EvaluationRun queued = evaluations.submit(new EvaluationRequest("eox-query", "1.2.0", "smoke", "success", 1000,
                "openclaw", "mcp-network", "llm-gateway"));
        assertThat(queued.runtimeId()).isEqualTo("openclaw");
        assertThat(queued.runtimeEnvironment().version()).isEqualTo("context-v1");
        assertThat(queued.runtimeEnvironment().revision()).isEqualTo(1);
        assertThat(queued.runtimeEnvironment().status()).isEqualTo(ExecutionEnvironmentStatus.ACTIVE);
        assertThat(queued.mcpServerEnvironment().version()).isEqualTo("context-v1");
        EvaluationRun completed = evaluations.awaitTerminal(queued.id(), Duration.ofSeconds(2));
        assertThat(completed.runtimeEnvironment().revision()).isEqualTo(1);
        assertThat(evaluations.snapshot(queued.id()).runtimeEnvironment().revision()).isEqualTo(1);

        environments.changeStatus("MCP_SERVER", "mcp-network",
                new ExecutionEnvironmentStatusRequest(ExecutionEnvironmentStatus.DEGRADED.name()),
                new Actor("admin", "admin"), "request-1");
        assertThatThrownBy(() -> evaluations.submit(new EvaluationRequest("eox-query", "1.2.0", "smoke", "success", 1000,
                "openclaw", "mcp-network", "llm-gateway")))
                .hasMessageContaining("not active");

        assertThat(evaluations.submit(new EvaluationRequest("eox-query", "1.2.0", "smoke", "success", 1000))
                .runtimeId()).isBlank();
    }

    @Test
    void newRunnerExecutionRejectsDegradedEnvironmentBeforePublishedVersionCheck() {
        GovernanceStore governance = new GovernanceStore(tempDir.resolve("runner-governance.json"), java.util.List.of());
        ExecutionEnvironmentStore store = new ExecutionEnvironmentStore(tempDir.resolve("runner-environments.json"),
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);
        ExecutionEnvironmentService environments = new ExecutionEnvironmentService(store, governance, clock);
        SkillExecutionService executions = new SkillExecutionService(new MockRunner(), governance,
                new com.huawei.skillcenter.operations.RuntimeSummaryService(new com.huawei.skillcenter.operations.RuntimeSummaryStore(), clock),
                new SkillExecutionStore(), clock, environments);

        environments.changeStatus("MCP_SERVER", "mcp-network",
                new ExecutionEnvironmentStatusRequest(ExecutionEnvironmentStatus.DEGRADED.name()),
                new Actor("admin", "admin"), "request-runner-1");

        assertThatThrownBy(() -> executions.execute(
                new SkillExecutionRequest("eox-query", "1.2.0", "success", 1000,
                        "openclaw", "mcp-network", "llm-gateway"),
                new Actor("admin", "admin"), "request-runner-2"))
                .hasMessageContaining("not active");
    }
}
