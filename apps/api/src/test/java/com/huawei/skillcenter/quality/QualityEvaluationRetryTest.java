package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QualityEvaluationRetryTest {
    @Test
    void retriesTransientRunnerFailureAndKeepsFinalQualityResultTraceable() {
        FlakyRunner runner = new FlakyRunner();
        QualityEvaluationService service = new QualityEvaluationService(
                runner,
                new MockEvaluationProvider(),
                new ProviderRetryPolicy(2, 0, 0, Set.of("UPSTREAM_TIMEOUT")),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun queued = service.submit(new EvaluationRequest(
                "skill-a", "1.0.0", "smoke", "success", 1_000));
        EvaluationRun completed = awaitCompleted(service, queued.id());

        assertThat(completed.status()).isEqualTo(EvaluationRunStatus.COMPLETED);
        assertThat(completed.passedCases()).isEqualTo(completed.totalCases());
        assertThat(completed.errorCode()).isBlank();
        assertThat(runner.calls()).isEqualTo(3);
        assertThat(service.snapshot(queued.id()).dataSource()).isEqualTo("mock");
    }

    @Test
    void doesNotRetryPermanentRunnerFailure() {
        PermanentFailureRunner runner = new PermanentFailureRunner();
        QualityEvaluationService service = new QualityEvaluationService(
                runner,
                new MockEvaluationProvider(),
                new ProviderRetryPolicy(5, 0, 0, Set.of("UPSTREAM_TIMEOUT", "RATE_LIMITED")),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun queued = service.submit(new EvaluationRequest(
                "skill-a", "1.0.0", "smoke", "success", 1_000));
        EvaluationRun completed = awaitCompleted(service, queued.id());

        assertThat(completed.status()).isEqualTo(EvaluationRunStatus.COMPLETED);
        assertThat(completed.errorCode()).isEqualTo("INVALID_SKILL");
        assertThat(runner.calls()).isEqualTo(2); // one call per case, no retry
        assertThat(service.snapshot(queued.id())).isNotNull();
    }

    @Test
    void stopsTransientFailuresAtTheConfiguredAttemptBudget() {
        AlwaysTimeoutRunner runner = new AlwaysTimeoutRunner();
        QualityEvaluationService service = new QualityEvaluationService(
                runner,
                new MockEvaluationProvider(),
                new ProviderRetryPolicy(3, 0, 0, Set.of("UPSTREAM_TIMEOUT")),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun queued = service.submit(new EvaluationRequest(
                "skill-a", "1.0.0", "smoke", "success", 1_000));
        EvaluationRun timedOut = awaitTerminal(service, queued.id());

        assertThat(timedOut.status()).isEqualTo(EvaluationRunStatus.TIMED_OUT);
        assertThat(timedOut.errorCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(runner.calls()).isEqualTo(3); // timeout is terminal for the run after 3 attempts
        assertThat(service.snapshot(queued.id())).isNull();
    }

    private EvaluationRun awaitCompleted(QualityEvaluationService service, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        EvaluationRun current;
        do {
            current = service.find(id);
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

    private EvaluationRun awaitTerminal(QualityEvaluationService service, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        EvaluationRun current;
        do {
            current = service.find(id);
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

    private static final class FlakyRunner implements SkillRunner {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String providerId() {
            return "mock-runner";
        }

        @Override
        public String providerVersion() {
            return "1.0";
        }

        @Override
        public RunnerExecutionResult execute(RunnerExecutionRequest request) {
            int attempt = calls.incrementAndGet();
            if (attempt == 1) {
                return new RunnerExecutionResult(RunnerExecutionStatus.TIMED_OUT, providerId(), providerVersion(),
                        "mock", 10, "", "UPSTREAM_TIMEOUT");
            }
            return new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, providerId(), providerVersion(),
                    "mock", 10, "success-hash", "");
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class PermanentFailureRunner implements SkillRunner {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String providerId() {
            return "mock-runner";
        }

        @Override
        public String providerVersion() {
            return "1.0";
        }

        @Override
        public RunnerExecutionResult execute(RunnerExecutionRequest request) {
            calls.incrementAndGet();
            return new RunnerExecutionResult(RunnerExecutionStatus.FAILED, providerId(), providerVersion(),
                    "mock", 10, "", "INVALID_SKILL");
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class AlwaysTimeoutRunner implements SkillRunner {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String providerId() {
            return "mock-runner";
        }

        @Override
        public String providerVersion() {
            return "1.0";
        }

        @Override
        public RunnerExecutionResult execute(RunnerExecutionRequest request) {
            calls.incrementAndGet();
            return new RunnerExecutionResult(RunnerExecutionStatus.TIMED_OUT, providerId(), providerVersion(),
                    "mock", 10, "", "UPSTREAM_TIMEOUT");
        }

        int calls() {
            return calls.get();
        }
    }
}
