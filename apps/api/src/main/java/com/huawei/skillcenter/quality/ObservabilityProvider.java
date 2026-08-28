package com.huawei.skillcenter.quality;

public interface ObservabilityProvider {
    String providerId();

    String providerVersion();

    default java.util.List<String> capabilities() {
        return java.util.List.of();
    }

    default ProviderHealth health() {
        return ProviderHealth.up();
    }

    /** Optional local summary source; remote adapters may return an empty list. */
    default java.util.List<RunnerExecutionSummary> summaries() {
        return java.util.List.of();
    }

    void record(RunnerExecutionSummary summary);
}
