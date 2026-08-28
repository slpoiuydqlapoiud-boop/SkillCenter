package com.huawei.skillcenter.quality;

public interface EvaluationProvider {
    String providerId();

    String providerVersion();

    default java.util.List<String> capabilities() {
        return java.util.List.of();
    }

    default ProviderHealth health() {
        return ProviderHealth.up();
    }

    EvaluationResult evaluate(EvaluationCase evaluationCase, RunnerExecutionResult executionResult);
}
