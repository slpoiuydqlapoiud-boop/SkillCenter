package com.huawei.skillcenter.quality;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

@Component
@ConditionalOnProperty(name = "skill-center.providers.evaluation", havingValue = "mock", matchIfMissing = true)
public class MockEvaluationProvider implements EvaluationProvider {
    @Override
    public String providerId() {
        return "mock-evaluation";
    }

    @Override
    public String providerVersion() {
        return "1.0";
    }

    @Override
    public List<String> capabilities() {
        return List.of("evaluate", "score");
    }

    @Override
    public EvaluationResult evaluate(EvaluationCase evaluationCase, RunnerExecutionResult executionResult) {
        boolean passed = executionResult.status() == RunnerExecutionStatus.SUCCEEDED;
        return new EvaluationResult(passed, passed ? 100 : 0,
                passed ? "passed" : executionResult.errorCode());
    }
}
