package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerExecutionResultTest {
    @Test
    void rejectsIncompleteProviderResult() {
        assertThatThrownBy(() -> new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED,
                "", "1.0", "mock", 10, "hash", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerId");
        assertThatThrownBy(() -> new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED,
                "mock-runner", "", "mock", 10, "hash", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerVersion");
        assertThatThrownBy(() -> new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED,
                "mock-runner", "1.0", "", 10, "hash", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataSource");
    }

    @Test
    void rejectsNegativeDurationAndMissingStatus() {
        assertThatThrownBy(() -> new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED,
                "mock-runner", "1.0", "mock", -1, "hash", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMs");
        assertThatThrownBy(() -> new RunnerExecutionResult(null,
                "mock-runner", "1.0", "mock", 10, "hash", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }
}
