package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRetryPolicyTest {
    @Test
    void retriesOnlyKnownTransientErrorsWithinTheAttemptBudget() {
        ProviderRetryPolicy policy = new ProviderRetryPolicy(3, 10, 25,
                Set.of("UPSTREAM_TIMEOUT", "RATE_LIMITED"));
        RunnerExecutionResult transientFailure = result("UPSTREAM_TIMEOUT");
        RunnerExecutionResult permanentFailure = result("INVALID_SKILL");

        assertThat(policy.shouldRetry(transientFailure, 1)).isTrue();
        assertThat(policy.shouldRetry(transientFailure, 2)).isTrue();
        assertThat(policy.shouldRetry(transientFailure, 3)).isFalse();
        assertThat(policy.shouldRetry(permanentFailure, 1)).isFalse();
        assertThat(policy.backoffMs(1)).isEqualTo(10);
        assertThat(policy.backoffMs(2)).isEqualTo(20);
        assertThat(policy.backoffMs(3)).isEqualTo(25);
    }

    @Test
    void rejectsUnboundedOrInvalidRetryConfiguration() {
        assertThatThrownBy(() -> new ProviderRetryPolicy(0, 0, 0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxAttempts must be between 1 and 5");
        assertThatThrownBy(() -> new ProviderRetryPolicy(2, -1, 10, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("initialBackoffMs must be between 0 and 60000");
        assertThatThrownBy(() -> new ProviderRetryPolicy(2, 10, 5, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxBackoffMs must be at least initialBackoffMs");
    }

    private RunnerExecutionResult result(String errorCode) {
        return new RunnerExecutionResult(RunnerExecutionStatus.FAILED, "runner", "1.0", "production",
                12, "hash", errorCode);
    }
}
