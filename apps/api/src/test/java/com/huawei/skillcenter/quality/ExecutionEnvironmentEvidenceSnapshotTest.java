package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironment;
import com.huawei.skillcenter.execution.ExecutionEnvironmentKind;
import com.huawei.skillcenter.execution.ExecutionEnvironmentSnapshot;
import com.huawei.skillcenter.execution.ExecutionEnvironmentStatus;
import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionEnvironmentEvidenceSnapshotTest {
    @Test
    void snapshotCapturesEnvironmentVersionRevisionAndStatus() {
        ExecutionEnvironment environment = new ExecutionEnvironment(
                "runtime-a", ExecutionEnvironmentKind.AGENT_RUNTIME, "runtime-v2",
                ExecutionEnvironmentStatus.ACTIVE, List.of("execute"), "adapter", "secret://runtime/config",
                "admin", Instant.parse("2026-08-25T00:00:00Z"), "admin",
                Instant.parse("2026-08-25T00:00:00Z"), 7);

        ExecutionEnvironmentSnapshot snapshot = ExecutionEnvironmentSnapshot.from(environment);

        assertThat(snapshot.kind()).isEqualTo(ExecutionEnvironmentKind.AGENT_RUNTIME);
        assertThat(snapshot.environmentId()).isEqualTo("runtime-a");
        assertThat(snapshot.version()).isEqualTo("runtime-v2");
        assertThat(snapshot.revision()).isEqualTo(7);
        assertThat(snapshot.status()).isEqualTo(ExecutionEnvironmentStatus.ACTIVE);
    }

    @Test
    void evaluationAndRunnerEvidenceKeepTheCapturedEnvironmentSnapshot() {
        ExecutionEnvironmentSnapshot runtime = new ExecutionEnvironmentSnapshot(
                ExecutionEnvironmentKind.AGENT_RUNTIME, "runtime-a", "runtime-v2", 7,
                ExecutionEnvironmentStatus.ACTIVE);
        EvaluationRun run = new EvaluationRun("run-1", "skill-a", "1.0.0", "smoke", "smoke-v1",
                EvaluationRunStatus.QUEUED, "mock-runner", "mock-evaluation", "mock",
                Instant.parse("2026-08-25T00:00:00Z"), null, 1, 0, 0, "", QualityGateStatus.BLOCKED,
                List.of("EVALUATION_NOT_COMPLETED"), "runtime-a", "", "", "", runtime,
                ExecutionEnvironmentSnapshot.empty(ExecutionEnvironmentKind.MCP_SERVER),
                ExecutionEnvironmentSnapshot.empty(ExecutionEnvironmentKind.LLM_PROVIDER));
        SkillExecutionRecord record = new SkillExecutionRecord(UUID.randomUUID(), "skill-a", "1.0.0",
                RunnerExecutionStatus.SUCCEEDED, "mock-runner", "v1", "mock", 12, "hash", "",
                Instant.parse("2026-08-25T00:00:00Z"), "runtime-a", "", "", runtime,
                ExecutionEnvironmentSnapshot.empty(ExecutionEnvironmentKind.MCP_SERVER),
                ExecutionEnvironmentSnapshot.empty(ExecutionEnvironmentKind.LLM_PROVIDER));

        assertThat(run.runtimeEnvironment()).isEqualTo(runtime);
        assertThat(record.runtimeEnvironment()).isEqualTo(runtime);
    }

    @Test
    void versionComparisonRejectsDifferentEnvironmentRevisionForSameEnvironmentId() {
        QualitySnapshot baseline = snapshot("baseline", "runtime-v1", 3);
        QualitySnapshot candidate = snapshot("candidate", "runtime-v1", 4);

        QualityComparison comparison = new QualityComparisonCalculator().compare(
                "skill-a", "1.0.0", "1.1.0", baseline, candidate,
                emptyRuntimeSnapshot(), emptyRuntimeSnapshot());

        assertThat(comparison.comparable()).isFalse();
        assertThat(comparison.reasonCode()).isEqualTo("EVALUATION_CONTEXT_MISMATCH");
        assertThat(comparison.baseline().runtimeEnvironment().revision()).isEqualTo(3);
        assertThat(comparison.candidate().runtimeEnvironment().revision()).isEqualTo(4);
    }

    private QualitySnapshot snapshot(String id, String environmentVersion, int revision) {
        return new QualitySnapshot(id, "skill-a", "1.0.0", "smoke", "smoke-v1", "mock-runner",
                "mock-evaluation", "mock", Instant.parse("2026-08-25T00:00:00Z"), 90, 1, 1,
                true, "quality-v1", 90, 1.0, QualityGateStatus.PASSED, List.of(), "runtime-a", "", "",
                new ExecutionEnvironmentSnapshot(ExecutionEnvironmentKind.AGENT_RUNTIME, "runtime-a",
                        environmentVersion, revision, ExecutionEnvironmentStatus.ACTIVE),
                ExecutionEnvironmentSnapshot.empty(ExecutionEnvironmentKind.MCP_SERVER),
                ExecutionEnvironmentSnapshot.empty(ExecutionEnvironmentKind.LLM_PROVIDER));
    }

    private RuntimeOperationsSnapshot emptyRuntimeSnapshot() {
        return new RuntimeOperationsSnapshot("24h", Instant.parse("2026-08-25T00:00:00Z"), "mock", null,
                RuntimeOperationsSnapshot.Totals.empty(), RuntimeOperationsSnapshot.Latency.empty(),
                List.of(), List.of(), List.of(), List.of());
    }
}
