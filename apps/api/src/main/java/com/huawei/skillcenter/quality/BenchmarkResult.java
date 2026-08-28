package com.huawei.skillcenter.quality;

import java.time.Instant;

public record BenchmarkResult(
        String benchmarkId,
        String skillId,
        String baselineVersion,
        String candidateVersion,
        String window,
        String dataSource,
        String conclusion,
        Instant createdAt,
        QualityComparison comparison,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        String suiteId,
        String suiteVersion,
        String experimentId
) {
    public BenchmarkResult(String benchmarkId, String skillId, String baselineVersion, String candidateVersion,
                           String window, String dataSource, String conclusion, Instant createdAt,
                           QualityComparison comparison, String runtimeId, String mcpServerId,
                           String llmProviderId, String suiteId, String suiteVersion) {
        this(benchmarkId, skillId, baselineVersion, candidateVersion, window, dataSource, conclusion, createdAt,
                comparison, runtimeId, mcpServerId, llmProviderId, suiteId, suiteVersion, "");
    }

    public BenchmarkResult(String benchmarkId, String skillId, String baselineVersion, String candidateVersion,
                           String window, String dataSource, String conclusion, Instant createdAt,
                           QualityComparison comparison) {
        this(benchmarkId, skillId, baselineVersion, candidateVersion, window, dataSource, conclusion, createdAt,
                comparison, "", "", "");
    }

    public BenchmarkResult(String benchmarkId, String skillId, String baselineVersion, String candidateVersion,
                           String window, String dataSource, String conclusion, Instant createdAt,
                           QualityComparison comparison, String runtimeId, String mcpServerId,
                           String llmProviderId) {
        this(benchmarkId, skillId, baselineVersion, candidateVersion, window, dataSource, conclusion, createdAt,
                comparison, runtimeId, mcpServerId, llmProviderId, "", "");
    }

    public BenchmarkResult {
        if (benchmarkId == null || benchmarkId.isBlank()) throw new IllegalArgumentException("benchmarkId must not be blank");
        if (skillId == null || skillId.isBlank()) throw new IllegalArgumentException("skillId must not be blank");
        if (baselineVersion == null || baselineVersion.isBlank()) throw new IllegalArgumentException("baselineVersion must not be blank");
        if (candidateVersion == null || candidateVersion.isBlank()) throw new IllegalArgumentException("candidateVersion must not be blank");
        if (window == null || window.isBlank()) throw new IllegalArgumentException("window must not be blank");
        dataSource = dataSource == null || dataSource.isBlank() ? "all" : dataSource;
        conclusion = conclusion == null || conclusion.isBlank() ? "NOT_COMPARABLE" : conclusion;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        runtimeId = environmentId(runtimeId);
        mcpServerId = environmentId(mcpServerId);
        llmProviderId = environmentId(llmProviderId);
        suiteId = suiteId == null ? "" : suiteId.trim();
        suiteVersion = suiteVersion == null ? "" : suiteVersion.trim();
        experimentId = experimentId == null ? "" : experimentId.trim();
        if (!experimentId.isBlank() && !experimentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("experimentId must be a bounded identifier");
        }
    }

    private static String environmentId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return "";
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("execution environment identifier is invalid");
        }
        return normalized;
    }
}
