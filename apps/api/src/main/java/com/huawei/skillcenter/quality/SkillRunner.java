package com.huawei.skillcenter.quality;

public interface SkillRunner {
    String providerId();

    String providerVersion();

    default java.util.List<String> capabilities() {
        return java.util.List.of();
    }

    default ProviderHealth health() {
        return ProviderHealth.up();
    }

    RunnerExecutionResult execute(RunnerExecutionRequest request);
}
