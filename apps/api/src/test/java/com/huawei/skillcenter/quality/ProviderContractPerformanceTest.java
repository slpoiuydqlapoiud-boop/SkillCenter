package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderContractPerformanceTest {
    @Test
    void mockRunnerAndEvaluationProviderStayWithinTheMvpExecutionBudget() {
        MockRunner runner = new MockRunner();
        MockEvaluationProvider evaluator = new MockEvaluationProvider();
        EvaluationCase evaluationCase = new EvaluationCase("case-1", "synthetic case");
        long started = System.nanoTime();
        int passed = 0;

        for (int index = 0; index < 10_000; index++) {
            RunnerExecutionResult execution = runner.execute(new RunnerExecutionRequest(
                    "skill-a", "1.0.0", "run-" + index, "smoke", "case-1", 1_000, "success"));
            if (evaluator.evaluate(evaluationCase, execution).passed()) {
                passed++;
            }
        }

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(passed).isEqualTo(10_000);
        assertThat(elapsedMs)
                .as("deterministic Mock Runner + evaluator should remain inside the MVP performance budget")
                .isLessThan(5_000);
    }
}
