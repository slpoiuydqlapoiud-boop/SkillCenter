package com.huawei.skillcenter.quality;

import java.util.List;
import java.util.Locale;

public record CompatibilityMatrixCreateRequest(
        String skillId,
        String skillVersion,
        String suiteId,
        List<String> runtimeIds,
        List<String> mcpServerIds,
        List<String> llmProviderIds,
        CompatibilityMatrixPolicy policy,
        double minimumPassRate,
        boolean releaseGateRequired,
        String scenario,
        long timeoutMs,
        String dataSource,
        String suiteVersion
) {
    public CompatibilityMatrixCreateRequest(String skillId, String skillVersion, String suiteId,
                                             List<String> runtimeIds, List<String> mcpServerIds,
                                             List<String> llmProviderIds, String policy,
                                             double minimumPassRate, boolean releaseGateRequired,
                                             String scenario, long timeoutMs) {
        this(skillId, skillVersion, suiteId, runtimeIds, mcpServerIds, llmProviderIds,
                CompatibilityMatrixPolicy.from(policy), minimumPassRate, releaseGateRequired,
                scenario, timeoutMs, "mock", "");
    }

    public CompatibilityMatrixCreateRequest(String skillId, String skillVersion, String suiteId,
                                             String suiteVersion, List<String> runtimeIds,
                                             List<String> mcpServerIds, List<String> llmProviderIds,
                                             String policy, double minimumPassRate, boolean releaseGateRequired,
                                             String scenario, long timeoutMs) {
        this(skillId, skillVersion, suiteId, runtimeIds, mcpServerIds, llmProviderIds,
                CompatibilityMatrixPolicy.from(policy), minimumPassRate, releaseGateRequired,
                scenario, timeoutMs, "mock", suiteVersion);
    }

    public CompatibilityMatrixCreateRequest {
        skillId = required(skillId, "skillId");
        skillVersion = required(skillVersion, "skillVersion");
        suiteId = required(suiteId, "suiteId");
        suiteVersion = suiteVersion == null ? "" : suiteVersion.trim();
        if (policy == null) throw new IllegalArgumentException("policy is required");
        if (Double.isNaN(minimumPassRate) || minimumPassRate < 0 || minimumPassRate > 1) {
            throw new IllegalArgumentException("minimumPassRate must be between 0 and 1");
        }
        if (policy == CompatibilityMatrixPolicy.ALL_MUST_PASS && Double.compare(minimumPassRate, 1d) != 0) {
            throw new IllegalArgumentException("minimumPassRate must be 1 for ALL_MUST_PASS");
        }
        scenario = scenario == null || scenario.isBlank() ? "success" : scenario.trim().toLowerCase(Locale.ROOT);
        if (!List.of("success", "failure", "timeout", "cancel", "cancelled").contains(scenario)) {
            throw new IllegalArgumentException("scenario is not supported");
        }
        timeoutMs = timeoutMs == 0 ? 1_000 : timeoutMs;
        if (timeoutMs < 1 || timeoutMs > 120_000) {
            throw new IllegalArgumentException("timeoutMs must be between 1 and 120000");
        }
        dataSource = dataSource == null || dataSource.isBlank() ? "mock" : dataSource.trim().toLowerCase(Locale.ROOT);
        if (!List.of("mock", "production").contains(dataSource)) {
            throw new IllegalArgumentException("dataSource must be mock or production");
        }
        runtimeIds = normalizeList(runtimeIds, "runtimeIds");
        mcpServerIds = normalizeList(mcpServerIds, "mcpServerIds");
        llmProviderIds = normalizeList(llmProviderIds, "llmProviderIds");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static List<String> normalizeList(List<String> values, String field) {
        return CompatibilityMatrixCombinationBuilder.normalizeDimension(values, field);
    }
}
