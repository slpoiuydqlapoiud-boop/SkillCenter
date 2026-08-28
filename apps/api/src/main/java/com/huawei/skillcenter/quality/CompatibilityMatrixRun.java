package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CompatibilityMatrixRun(
        String matrixRunId,
        String skillId,
        String skillVersion,
        String suiteId,
        String suiteVersion,
        CompatibilityMatrixPolicy policy,
        double minimumPassRate,
        boolean releaseGateRequired,
        CompatibilityMatrixStatus status,
        String dataSource,
        String scenario,
        long timeoutMs,
        int totalCases,
        int completedCases,
        int passedCases,
        int score,
        double passRate,
        QualityGateStatus gateStatus,
        List<String> gateReasons,
        String createdBy,
        Instant createdAt,
        Instant completedAt
) {
    public CompatibilityMatrixRun {
        if (matrixRunId == null || !isUuid(matrixRunId)) throw new IllegalArgumentException("matrixRunId must be a UUID");
        skillId = required(skillId, "skillId");
        skillVersion = required(skillVersion, "skillVersion");
        suiteId = required(suiteId, "suiteId");
        suiteVersion = required(suiteVersion, "suiteVersion");
        if (policy == null) throw new IllegalArgumentException("policy is required");
        if (Double.isNaN(minimumPassRate) || minimumPassRate < 0 || minimumPassRate > 1) {
            throw new IllegalArgumentException("minimumPassRate must be between 0 and 1");
        }
        if (status == null) throw new IllegalArgumentException("status is required");
        dataSource = required(dataSource, "dataSource").toLowerCase(java.util.Locale.ROOT);
        if (!List.of("mock", "production").contains(dataSource)) throw new IllegalArgumentException("dataSource is invalid");
        scenario = scenario == null || scenario.isBlank() ? "success" : scenario.trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("success", "failure", "timeout", "cancel", "cancelled").contains(scenario)) {
            throw new IllegalArgumentException("scenario is not supported");
        }
        if (timeoutMs < 1 || timeoutMs > 120_000) throw new IllegalArgumentException("timeoutMs must be between 1 and 120000");
        if (totalCases < 0 || completedCases < 0 || passedCases < 0 || completedCases > totalCases || passedCases > completedCases) {
            throw new IllegalArgumentException("matrix case counters are invalid");
        }
        if (score < 0 || score > 100 || Double.isNaN(passRate) || passRate < 0 || passRate > 1) {
            throw new IllegalArgumentException("matrix quality values are invalid");
        }
        if (gateStatus == null) throw new IllegalArgumentException("gateStatus is required");
        gateReasons = List.copyOf(gateReasons == null ? List.of() : gateReasons);
        createdBy = required(createdBy, "createdBy");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (status.terminal() && completedAt == null) throw new IllegalArgumentException("completedAt is required for terminal matrix");
        if (completedAt != null && completedAt.isBefore(createdAt)) throw new IllegalArgumentException("completedAt must not be before createdAt");
    }

    private static boolean isUuid(String value) {
        try { UUID.fromString(value); return true; } catch (IllegalArgumentException exception) { return false; }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
