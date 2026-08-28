package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockRunnerTest {
    private final MockRunner runner = new MockRunner();

    @Test
    void sameRequestProducesDeterministicSuccessfulResult() {
        RunnerExecutionRequest request = new RunnerExecutionRequest(
                "eox-query", "1.2.0", "run-1", "smoke", "case-1", 1_000, "success");

        RunnerExecutionResult first = runner.execute(request);
        RunnerExecutionResult second = runner.execute(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.status()).isEqualTo(RunnerExecutionStatus.SUCCEEDED);
        assertThat(first.dataSource()).isEqualTo("mock");
        assertThat(first.providerId()).isEqualTo("mock-runner");
        assertThat(first.outputHash()).isNotBlank();
    }

    @Test
    void controlledScenariosReturnStandardizedFailureStates() {
        assertThat(runner.execute(request("failure")).status())
                .isEqualTo(RunnerExecutionStatus.FAILED);
        assertThat(runner.execute(request("timeout")).status())
                .isEqualTo(RunnerExecutionStatus.TIMED_OUT);
        assertThat(runner.execute(request("cancel")).status())
                .isEqualTo(RunnerExecutionStatus.CANCELLED);
    }

    @Test
    void mockObservabilityStoresOnlyAExecutionSummary() {
        MockObservabilityProvider observability = new MockObservabilityProvider();
        observability.record(new RunnerExecutionSummary("run-1", "eox-query", "1.2.0",
                RunnerExecutionStatus.SUCCEEDED, 42, "MOCK", "mock"));

        assertThat(observability.summaries()).singleElement().satisfies(summary -> {
            assertThat(summary.runId()).isEqualTo("run-1");
            assertThat(summary.status()).isEqualTo(RunnerExecutionStatus.SUCCEEDED);
            assertThat(summary).hasNoNullFieldsOrProperties();
        });
    }

    private RunnerExecutionRequest request(String scenario) {
        return new RunnerExecutionRequest("eox-query", "1.2.0", "run-1", "smoke", "case-1", 1_000, scenario);
    }
}
