package com.huawei.skillcenter.quality;

public record RunnerExecutionResult(
        RunnerExecutionStatus status,
        String providerId,
        String providerVersion,
        String dataSource,
        long durationMs,
        String outputHash,
        String errorCode
) {
    public RunnerExecutionResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        require(providerId, "providerId");
        require(providerVersion, "providerVersion");
        require(dataSource, "dataSource");
        if (durationMs < 0) throw new IllegalArgumentException("durationMs must not be negative");
        outputHash = outputHash == null ? "" : outputHash;
        errorCode = errorCode == null ? "" : errorCode;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
