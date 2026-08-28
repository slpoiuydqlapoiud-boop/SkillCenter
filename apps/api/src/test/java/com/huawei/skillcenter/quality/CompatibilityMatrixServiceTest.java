package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.execution.ExecutionEnvironmentService;
import com.huawei.skillcenter.execution.ExecutionEnvironmentStatus;
import com.huawei.skillcenter.execution.ExecutionEnvironmentStatusRequest;
import com.huawei.skillcenter.execution.ExecutionEnvironmentStore;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompatibilityMatrixServiceTest {
    @TempDir
    Path tempDir;

    private final Actor admin = new Actor("matrix-admin", "admin");
    private Clock clock;
    private QualityEvidenceStore evidence;
    private CompatibilityMatrixService service;
    private ExecutionEnvironmentService environments;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        GovernanceStore governance = new GovernanceStore(tempDir.resolve(UUID.randomUUID() + "-governance.json"), List.of());
        ExecutionEnvironmentStore environmentStore = new ExecutionEnvironmentStore(
                tempDir.resolve(UUID.randomUUID() + "-environments.json"), mapper, clock);
        environments = new ExecutionEnvironmentService(environmentStore, governance, clock);
        evidence = new QualityEvidenceStore(tempDir.resolve(UUID.randomUUID() + "-quality.json"), mapper);
        QualityEvaluationService evaluations = new QualityEvaluationService(new MockRunner(),
                new MockEvaluationProvider(), clock, evidence, environments);
        service = new CompatibilityMatrixService(evaluations, environments, evidence, governance, clock);
    }

    @Test
    void createRunsEveryCombinationAndAggregatesCompletedEvidence() {
        CompatibilityMatrixRun submitted = service.create(request(List.of("openclaw"), List.of("mcp-network"),
                List.of("llm-gateway"), false), admin, "request-1");

        CompatibilityMatrixRun completed = awaitTerminal(submitted.matrixRunId());

        assertThat(completed.status()).isEqualTo(CompatibilityMatrixStatus.COMPLETED);
        assertThat(completed.totalCases()).isEqualTo(1);
        assertThat(completed.completedCases()).isEqualTo(1);
        assertThat(completed.passedCases()).isEqualTo(1);
        assertThat(completed.gateStatus()).isEqualTo(QualityGateStatus.PASSED);
        assertThat(service.cases(submitted.matrixRunId(), admin)).singleElement()
                .satisfies(matrixCase -> {
                    assertThat(matrixCase.evaluationRunId()).isNotBlank();
                    assertThat(matrixCase.runtimeVersion()).isEqualTo("context-v1");
                    assertThat(matrixCase.runtimeEnvironment().capabilities()).containsExactly("context-only");
                    assertThat(matrixCase.runtimeEnvironment().adapterProviderId()).isEqualTo("openclaw-runner");
                    assertThat(matrixCase.status()).isEqualTo(CompatibilityMatrixCaseStatus.COMPLETED);
                });
    }

    @Test
    void createRejectsEnvironmentThatIsNotActive() {
        environments.changeStatus("MCP_SERVER", "mcp-network",
                new ExecutionEnvironmentStatusRequest(ExecutionEnvironmentStatus.DEGRADED.name()), admin, "request-status");

        assertThatThrownBy(() -> service.create(request(List.of("openclaw"), List.of("mcp-network"),
                List.of("llm-gateway"), false), admin, "request-2"))
                .hasMessageContaining("not active");
    }

    @Test
    void createRejectsSecondActiveReleaseGateMatrix() {
        Instant now = clock.instant();
        String matrixId = UUID.randomUUID().toString();
        CompatibilityMatrixRun active = new CompatibilityMatrixRun(matrixId, "eox-query", "1.3.0", "smoke", "smoke-v1",
                CompatibilityMatrixPolicy.ALL_MUST_PASS, 1, true, CompatibilityMatrixStatus.QUEUED,
                "mock", "success", 1_000, 1, 0, 0, 0, 0, QualityGateStatus.BLOCKED,
                List.of("EVALUATION_NOT_COMPLETED"), admin.userId(), now, null);
        CompatibilityMatrixCase queued = new CompatibilityMatrixCase("case-1", matrixId, "", "openclaw", "mcp-network", "llm-gateway",
                "context-v1", "context-v1", "context-v1", ExecutionEnvironmentStatus.ACTIVE,
                ExecutionEnvironmentStatus.ACTIVE, ExecutionEnvironmentStatus.ACTIVE, CompatibilityMatrixCaseStatus.QUEUED,
                0, QualityGateStatus.BLOCKED, List.of("EVALUATION_NOT_COMPLETED"), "", null, null);
        evidence.save(new QualityEvidenceState(List.of(), null, List.of(), List.of(), List.of(), List.of(active), List.of(queued)));

        assertThatThrownBy(() -> service.create(request(List.of("openclaw"), List.of("mcp-network"),
                List.of("llm-gateway"), true), admin, "request-3"))
                .hasMessageContaining("active release compatibility matrix");
    }

    @Test
    void developerCannotCreateOrReadMatrix() {
        Actor developer = new Actor("developer", "developer");
        assertThatThrownBy(() -> service.create(request(List.of("openclaw"), List.of(), List.of(), false), developer, "request-4"))
                .hasMessageContaining("permission");
    }

    @Test
    void resumesQueuedMatricesAfterServiceRestart() {
        Instant now = clock.instant();
        String matrixId = UUID.randomUUID().toString();
        CompatibilityMatrixRun queued = new CompatibilityMatrixRun(matrixId, "eox-query", "1.3.0", "smoke", "smoke-v1",
                CompatibilityMatrixPolicy.ALL_MUST_PASS, 1, false, CompatibilityMatrixStatus.QUEUED, "mock", "success",
                1_000, 1, 0, 0, 0, 0, QualityGateStatus.BLOCKED,
                List.of("EVALUATION_NOT_COMPLETED"), admin.userId(), now, null);
        CompatibilityMatrixCase matrixCase = new CompatibilityMatrixCase("restart-case", matrixId, "", "openclaw", "", "",
                "context-v1", "", "", ExecutionEnvironmentStatus.ACTIVE, null, null,
                CompatibilityMatrixCaseStatus.QUEUED, 0, QualityGateStatus.BLOCKED,
                List.of("EVALUATION_NOT_COMPLETED"), "", null, null);
        evidence.save(new QualityEvidenceState(List.of(), null, List.of(), List.of(), List.of(), List.of(queued), List.of(matrixCase)));

        service.resumeActiveMatrices();

        assertThat(awaitTerminal(matrixId).status()).isEqualTo(CompatibilityMatrixStatus.COMPLETED);
    }

    @Test
    void cancellingRunningMatrixCancelsTheChildEvaluation() throws Exception {
        SlowRunner slowRunner = new SlowRunner();
        QualityEvaluationService slowEvaluations = new QualityEvaluationService(slowRunner,
                new MockEvaluationProvider(), clock, evidence, environments);
        CompatibilityMatrixService cancellable = new CompatibilityMatrixService(slowEvaluations, environments,
                evidence, new GovernanceStore(tempDir.resolve(UUID.randomUUID() + "-cancel-governance.json"), List.of()), clock);
        CompatibilityMatrixRun submitted = cancellable.create(request(List.of("openclaw"), List.of(), List.of(), false),
                admin, "request-cancel");
        String childId = awaitRunningCase(cancellable, submitted.matrixRunId());
        assertThat(slowRunner.started.await(1, TimeUnit.SECONDS)).isTrue();

        CompatibilityMatrixRun cancelled = cancellable.cancel(submitted.matrixRunId(), admin, "request-cancel-now");

        assertThat(cancelled.status()).isEqualTo(CompatibilityMatrixStatus.CANCELLED);
        assertThat(slowEvaluations.find(childId).status()).isEqualTo(EvaluationRunStatus.CANCELLED);
        assertThat(cancellable.cases(submitted.matrixRunId(), admin)).singleElement()
                .satisfies(matrixCase -> assertThat(matrixCase.status()).isEqualTo(CompatibilityMatrixCaseStatus.CANCELLED));
        slowRunner.release.countDown();
        assertThat(slowRunner.finished.await(1, TimeUnit.SECONDS)).isTrue();
    }

    private CompatibilityMatrixCreateRequest request(List<String> runtimes, List<String> mcps,
                                                      List<String> llms, boolean releaseGateRequired) {
        return new CompatibilityMatrixCreateRequest("eox-query", "1.3.0", "smoke", runtimes, mcps, llms,
                "ALL_MUST_PASS", 1, releaseGateRequired, "success", 1_000);
    }

    private CompatibilityMatrixRun awaitTerminal(String matrixId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        CompatibilityMatrixRun current;
        do {
            current = service.find(matrixId, admin);
            if (current.status().terminal()) return current;
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        } while (System.nanoTime() < deadline);
        return current;
    }

    private String awaitRunningCase(CompatibilityMatrixService target, String matrixId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        do {
            List<CompatibilityMatrixCase> current = target.cases(matrixId, admin);
            if (!current.isEmpty() && current.get(0).status() == CompatibilityMatrixCaseStatus.RUNNING) {
                return current.get(0).evaluationRunId();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("matrix case did not reach RUNNING");
    }

    private static final class SlowRunner implements SkillRunner {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public String providerId() { return "slow-runner"; }

        @Override
        public String providerVersion() { return "1.0"; }

        @Override
        public RunnerExecutionResult execute(RunnerExecutionRequest request) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
            return new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, providerId(), providerVersion(),
                    "mock", 10, "slow-result", "");
        }
    }
}
