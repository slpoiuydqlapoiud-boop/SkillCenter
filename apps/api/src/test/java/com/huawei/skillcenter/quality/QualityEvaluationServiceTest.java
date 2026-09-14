package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityEvaluationServiceTest {
    private final QualityEvaluationService service = new QualityEvaluationService(
            new MockRunner(), new MockEvaluationProvider(),
            Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void evaluatesSmokeSuiteAndCreatesTraceableQualitySnapshot() {
        EvaluationRun run = service.submit(new EvaluationRequest(
                "eox-query", "1.2.0", "smoke", "success", 1_000));

        EvaluationRun completed = awaitCompleted(run.id());

        assertThat(completed.status()).isEqualTo(EvaluationRunStatus.COMPLETED);
        assertThat(completed.providerId()).isEqualTo("mock-runner");
        assertThat(completed.evaluationProviderId()).isEqualTo("mock-evaluation");
        assertThat(completed.dataSource()).isEqualTo("mock");
        assertThat(completed.totalCases()).isEqualTo(2);
        assertThat(completed.passedCases()).isEqualTo(2);
        assertThat(completed.score()).isEqualTo(100);

        QualitySnapshot snapshot = service.snapshot(completed.id());
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.skillId()).isEqualTo("eox-query");
        assertThat(snapshot.skillVersion()).isEqualTo("1.2.0");
        assertThat(snapshot.suiteVersion()).isEqualTo("smoke-v1");
        assertThat(snapshot.snapshotId()).isEqualTo(run.id());
    }

    @Test
    void preservesProductionDataSourceAcrossEvaluationEvidence() {
        QualityEvaluationService productionService = new QualityEvaluationService(
                new ProductionRunner(), new MockEvaluationProvider(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun queued = productionService.submit(new EvaluationRequest(
                "eox-query", "1.2.0", "smoke", "success", 1_000));

        assertThat(queued.dataSource()).isEqualTo("production");
        EvaluationRun completed = awaitCompleted(productionService, queued.id());

        assertThat(completed.dataSource()).isEqualTo("production");
        assertThat(productionService.snapshot(queued.id()).dataSource()).isEqualTo("production");
        assertThat(productionService.results(queued.id()))
                .extracting(EvaluationCaseResult::dataSource)
                .containsOnly("production");
        assertThat(productionService.snapshots("eox-query", "production", null, null, null)).hasSize(1);
        assertThat(productionService.snapshots("eox-query", "mock", null, null, null)).isEmpty();
    }

    @Test
    void failsClosedWhenRunnerMixesEvidenceDataSourcesAcrossCases() {
        QualityEvaluationService mixedService = new QualityEvaluationService(
                new MixedDataSourceRunner(), new MockEvaluationProvider(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun queued = mixedService.submit(new EvaluationRequest(
                "eox-query", "1.2.0", "smoke", "success", 1_000));
        EvaluationRun terminal = awaitTerminal(mixedService, queued.id());

        assertThat(terminal.status()).isEqualTo(EvaluationRunStatus.FAILED);
        assertThat(terminal.errorCode()).isEqualTo("MIXED_DATA_SOURCE");
        assertThat(mixedService.snapshot(queued.id())).isNull();
        assertThat(mixedService.results(queued.id()))
                .extracting(EvaluationCaseResult::dataSource)
                .containsOnly("production");
    }

    @Test
    void filtersQualitySnapshotsByDataSource() {
        EvaluationRun run = service.submit(new EvaluationRequest(
                "eox-query", "1.2.1", "smoke", "success", 1_000));
        awaitCompleted(run.id());

        assertThat(service.snapshots("eox-query", "mock", null, null, null)).hasSize(1);
        assertThat(service.snapshots("eox-query", "production", null, null, null)).isEmpty();
    }

    @Test
    void filtersEvaluationHistoryByDataSourceAndExecutionEnvironment() {
        EvaluationRun openClaw = service.submit(new EvaluationRequest(
                "eox-query", "1.2.0", "smoke", "success", 1_000,
                "openclaw", "mcp-network", "llm-gateway"));
        EvaluationRun otherRuntime = service.submit(new EvaluationRequest(
                "eox-query", "1.2.1", "smoke", "success", 1_000,
                "other-runtime", "mcp-network", "llm-gateway"));
        awaitCompleted(openClaw.id());
        awaitCompleted(otherRuntime.id());

        assertThat(service.list("eox-query", "mock", "openclaw", "mcp-network", "llm-gateway"))
                .extracting(EvaluationRun::id)
                .containsExactly(openClaw.id());
        assertThat(service.list("eox-query", "production", "openclaw", "mcp-network", "llm-gateway"))
                .isEmpty();
    }

    @Test
    void reusesTheSameEvaluationRunForAnExperimentId() {
        EvaluationRequest request = new EvaluationRequest(
                "eox-query", "1.2.0", "smoke", "success", 1_000,
                "", "", "", "smoke-v1", "experiment-1");

        EvaluationRun first = service.submit(request);
        EvaluationRun second = service.submit(request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(service.list("eox-query"))
                .filteredOn(run -> "experiment-1".equals(run.experimentId()))
                .hasSize(1);
    }

    @Test
    void rejectsExperimentIdReuseWhenEvaluationContextDiffers() {
        service.submit(new EvaluationRequest("eox-query", "1.2.0", "smoke", "success", 1_000,
                "", "", "", "smoke-v1", "experiment-1"));

        assertThatThrownBy(() -> service.submit(new EvaluationRequest("eox-query", "1.2.1",
                "smoke", "success", 1_000, "", "", "", "smoke-v1", "experiment-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("experimentId");
    }

    @Test
    void rejectsMissingSkillVersionAndUnknownSuite() {
        assertThatThrownBy(() -> service.submit(new EvaluationRequest("eox-query", "", "smoke", "success", 1_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skillVersion");
        assertThatThrownBy(() -> service.submit(new EvaluationRequest("eox-query", "1.2.0", "unknown", "success", 1_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suiteId");
        assertThatThrownBy(() -> service.submit(new EvaluationRequest("eox-query", "1.2.0", "smoke", "success", -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
    }

    @Test
    void supportsManagedSuitesAndVersionedQualityRules() {
        assertThat(service.listSuites()).extracting(EvaluationSuite::id).contains("smoke");

        EvaluationSuite suite = service.createSuite(new EvaluationSuiteRequest(
                "release", "Release regression", "release-v1", true,
                java.util.List.of(new EvaluationCase("case-1", "发布路径"))));
        assertThat(suite.id()).isEqualTo("release");
        assertThat(service.listSuites()).extracting(EvaluationSuite::id).contains("release");

        QualityRuleSet rules = service.updateRules(new QualityRuleSet("default", "quality-v2", 95, 1.0, 100));
        assertThat(rules.version()).isEqualTo("quality-v2");
        assertThat(service.rules()).isEqualTo(rules);
    }

    @Test
    void cancelsRunningEvaluationAndDoesNotCreateQualitySnapshot() throws Exception {
        BlockingRunner runner = new BlockingRunner();
        QualityEvaluationService cancellable = new QualityEvaluationService(
                runner, new MockEvaluationProvider(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun queued = cancellable.submit(new EvaluationRequest(
                "eox-query", "1.2.0", "smoke", "success", 1_000));

        assertThat(runner.started.await(1, TimeUnit.SECONDS)).isTrue();
        EvaluationRun cancelled = cancellable.cancel(queued.id());

        assertThat(cancelled.status()).isEqualTo(EvaluationRunStatus.CANCELLED);
        assertThat(cancelled.errorCode()).isEqualTo("EVALUATION_CANCELLED");
        assertThat(cancelled.gateReasons()).containsExactly("EVALUATION_CANCELLED");

        runner.release.countDown();
        Thread.sleep(50);
        assertThat(cancellable.find(queued.id()).status()).isEqualTo(EvaluationRunStatus.CANCELLED);
        assertThat(cancellable.snapshot(queued.id())).isNull();
    }

    @Test
    void awaitsCancellationWorkerQuiescenceBeforeTemporaryStateCleanup() throws Exception {
        BlockingRunner runner = new BlockingRunner();
        QualityEvaluationService cancellable = new QualityEvaluationService(
                runner, new MockEvaluationProvider(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun queued = cancellable.submit(new EvaluationRequest(
                "eox-query", "1.2.0", "smoke", "success", 1_000));
        assertThat(runner.started.await(1, TimeUnit.SECONDS)).isTrue();

        cancellable.cancel(queued.id());
        assertThat(cancellable.awaitQuiescence(Duration.ofMillis(20))).isFalse();

        runner.release.countDown();
        assertThat(cancellable.awaitQuiescence(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void recordsRunnerTimeoutAsTerminalEvaluationTimeoutWithoutQualitySnapshot() {
        QualityEvaluationService timeoutService = new QualityEvaluationService(
                new MockRunner(), new MockEvaluationProvider(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun queued = timeoutService.submit(new EvaluationRequest(
                "eox-query", "1.2.0", "smoke", "timeout", 1_000));
        EvaluationRun terminal = awaitTerminal(timeoutService, queued.id());

        assertThat(terminal.status()).isEqualTo(EvaluationRunStatus.TIMED_OUT);
        assertThat(terminal.errorCode()).isEqualTo("MOCK_TIMED_OUT");
        assertThat(terminal.gateReasons()).containsExactly("EVALUATION_TIMED_OUT");
        assertThat(timeoutService.snapshot(queued.id())).isNull();
    }

    @Test
    void persistsRedactedPerCaseResultsForCompletedEvaluation() {
        EvaluationRun run = service.submit(new EvaluationRequest(
                "eox-query", "1.2.0", "smoke", "success", 1_000));

        EvaluationRun completed = awaitCompleted(run.id());
        assertThat(service.results(completed.id())).hasSize(2)
                .allSatisfy(result -> {
                    assertThat(result.runId()).isEqualTo(completed.id());
                    assertThat(result.caseId()).isNotBlank();
                    assertThat(result.caseName()).isNotBlank();
                    assertThat(result.passed()).isTrue();
                    assertThat(result.score()).isEqualTo(100);
                    assertThat(result.dataSource()).isEqualTo("mock");
                    assertThat(result.reason()).isEqualTo("passed");
                });
    }

    @Test
    void doesNotPersistBusinessTextFromEvaluationCaseNames() {
        service.createSuite(new EvaluationSuiteRequest("redaction", "Redaction", "redaction-v1", true,
                java.util.List.of(new EvaluationCase("case-secret", "customer prompt: do-not-store"))));
        EvaluationRun run = service.submit(new EvaluationRequest(
                "eox-query", "1.2.0", "redaction", "success", 1_000));

        awaitCompleted(run.id());
        EvaluationCaseResult result = service.results(run.id()).get(0);
        assertThat(result.caseName()).doesNotContain("customer prompt").isEqualTo("case-case-secret");
    }

    private EvaluationRun awaitCompleted(String id) {
        return awaitCompleted(service, id);
    }

    private EvaluationRun awaitCompleted(QualityEvaluationService target, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        EvaluationRun current;
        do {
            current = target.find(id);
            if (current.status() == EvaluationRunStatus.COMPLETED) {
                return current;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        } while (System.nanoTime() < deadline);
        return current;
    }

    private EvaluationRun awaitTerminal(QualityEvaluationService target, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        EvaluationRun current;
        do {
            current = target.find(id);
            if (current.status() == EvaluationRunStatus.TIMED_OUT
                    || current.status() == EvaluationRunStatus.FAILED
                    || current.status() == EvaluationRunStatus.CANCELLED
                    || current.status() == EvaluationRunStatus.COMPLETED) {
                return current;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        } while (System.nanoTime() < deadline);
        return current;
    }

    private static final class BlockingRunner implements SkillRunner {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String providerId() {
            return "mock-runner";
        }

        @Override
        public String providerVersion() {
            return "1.0";
        }

        @Override
        public java.util.List<String> capabilities() {
            return java.util.List.of("execute");
        }

        @Override
        public RunnerExecutionResult execute(RunnerExecutionRequest request) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new RunnerExecutionResult(RunnerExecutionStatus.CANCELLED, providerId(), providerVersion(),
                        "mock", 0, "", "EVALUATION_CANCELLED");
            }
            return new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, providerId(), providerVersion(),
                    "mock", 10, "blocking-success", "");
        }
    }

    private static final class ProductionRunner implements SkillRunner {
        @Override
        public String providerId() {
            return "openclaw-runner";
        }

        @Override
        public String providerVersion() {
            return "contract-v1";
        }

        @Override
        public String dataSource() {
            return "production";
        }

        @Override
        public RunnerExecutionResult execute(RunnerExecutionRequest request) {
            return new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, providerId(), providerVersion(),
                    "production", 10, "production-success", "");
        }
    }

    private static final class MixedDataSourceRunner implements SkillRunner {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String providerId() {
            return "openclaw-runner";
        }

        @Override
        public String providerVersion() {
            return "contract-v1";
        }

        @Override
        public String dataSource() {
            return "production";
        }

        @Override
        public RunnerExecutionResult execute(RunnerExecutionRequest request) {
            String dataSource = calls.getAndIncrement() == 0 ? "production" : "mock";
            return new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, providerId(), providerVersion(),
                    dataSource, 10, "mixed-source", "");
        }
    }
}
