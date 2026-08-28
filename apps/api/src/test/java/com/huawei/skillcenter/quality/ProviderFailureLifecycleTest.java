package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderFailureLifecycleTest {
    @Test
    void preservesStableProviderErrorCodeWithoutPersistingProviderMessage() {
        QualityEvaluationService service = new QualityEvaluationService(
                new UnavailableRunner(),
                new MockEvaluationProvider(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun queued = service.submit(new EvaluationRequest(
                "skill-a", "1.0.0", "smoke", "success", 1_000));
        EvaluationRun failed = awaitTerminal(service, queued.id());

        assertThat(failed.status()).isEqualTo(EvaluationRunStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("EXTERNAL_ADAPTER_NOT_CONFIGURED");
        assertThat(failed.errorCode()).doesNotContain("openclaw");
        assertThat(service.snapshot(queued.id())).isNull();
    }

    @Test
    void sanitizesFreeFormRunnerErrorCodesBeforeSavingCaseEvidence() {
        QualityEvaluationService service = new QualityEvaluationService(
                new NoisyFailureRunner(),
                new MockEvaluationProvider(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun queued = service.submit(new EvaluationRequest(
                "skill-a", "1.0.0", "smoke", "success", 1_000));
        EvaluationRun completed = awaitTerminal(service, queued.id());

        assertThat(completed.status()).isEqualTo(EvaluationRunStatus.COMPLETED);
        assertThat(completed.errorCode()).isEqualTo("RUNNER_EXECUTION_FAILED");
        assertThat(service.results(queued.id()))
                .extracting(EvaluationCaseResult::errorCode)
                .containsOnly("RUNNER_EXECUTION_FAILED");
        assertThat(service.results(queued.id()))
                .extracting(EvaluationCaseResult::reason)
                .containsOnly("RUNNER_EXECUTION_FAILED");
    }

    private EvaluationRun awaitTerminal(QualityEvaluationService service, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        EvaluationRun current;
        do {
            current = service.find(id);
            if (current.status() == EvaluationRunStatus.FAILED
                    || current.status() == EvaluationRunStatus.COMPLETED
                    || current.status() == EvaluationRunStatus.TIMED_OUT
                    || current.status() == EvaluationRunStatus.CANCELLED) {
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

    private static final class UnavailableRunner implements SkillRunner {
        @Override
        public String providerId() {
            return "openclaw-runner";
        }

        @Override
        public String providerVersion() {
            return "contract-v1";
        }

        @Override
        public RunnerExecutionResult execute(RunnerExecutionRequest request) {
            throw new ProviderUnavailableException(providerId(), "EXTERNAL_ADAPTER_NOT_CONFIGURED");
        }
    }

    private static final class NoisyFailureRunner implements SkillRunner {
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
            return new RunnerExecutionResult(RunnerExecutionStatus.FAILED, providerId(), providerVersion(),
                    "mock", 10, "", "customer prompt: do-not-store");
        }
    }
}
