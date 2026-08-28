package com.huawei.skillcenter.release;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record ReleaseGateSnapshot(
        Instant checkedAt,
        String outcome,
        List<String> reasonCodes,
        String qualitySnapshotId,
        String optimizationExperimentId,
        String optimizationDecision,
        String compatibilityMatrixId,
        String dataSource,
        String suiteId,
        String suiteVersion,
        String runtimeId,
        String mcpServerId,
        String llmProviderId
) {
    private static final Set<String> OUTCOMES = Set.of("PASSED", "BLOCKED", "NO_EVIDENCE");

    public ReleaseGateSnapshot {
        checkedAt = checkedAt == null ? Instant.now() : checkedAt;
        outcome = normalizeCode(outcome, "outcome");
        if (!OUTCOMES.contains(outcome)) throw new IllegalArgumentException("outcome is not supported");
        reasonCodes = normalizeReasonCodes(reasonCodes);
        qualitySnapshotId = normalizeOptionalIdentifier(qualitySnapshotId, "qualitySnapshotId");
        optimizationExperimentId = normalizeOptionalIdentifier(optimizationExperimentId, "optimizationExperimentId");
        optimizationDecision = normalizeOptionalCode(optimizationDecision, "optimizationDecision");
        compatibilityMatrixId = normalizeOptionalIdentifier(compatibilityMatrixId, "compatibilityMatrixId");
        dataSource = normalizeDataSource(dataSource);
        suiteId = normalizeOptionalIdentifier(suiteId, "suiteId");
        suiteVersion = normalizeOptionalIdentifier(suiteVersion, "suiteVersion");
        if (suiteId.isBlank() != suiteVersion.isBlank()) {
            throw new IllegalArgumentException("suiteId and suiteVersion must be provided together");
        }
        runtimeId = normalizeOptionalIdentifier(runtimeId, "runtimeId");
        mcpServerId = normalizeOptionalIdentifier(mcpServerId, "mcpServerId");
        llmProviderId = normalizeOptionalIdentifier(llmProviderId, "llmProviderId");
    }

    public static ReleaseGateSnapshot passed(Instant checkedAt) {
        return new ReleaseGateSnapshot(checkedAt, "PASSED", List.of(), "", "", "", "", "all",
                "", "", "", "", "");
    }

    private static List<String> normalizeReasonCodes(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 20) throw new IllegalArgumentException("reasonCodes must contain at most 20 items");
        return values.stream().map(value -> normalizeCode(value, "reasonCode")).distinct().toList();
    }

    private static String normalizeDataSource(String value) {
        String normalized = value == null || value.isBlank() ? "all" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("mock", "production", "all").contains(normalized)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
        return normalized;
    }

    private static String normalizeOptionalCode(String value, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.isBlank() && (normalized.length() > 64 || !normalized.matches("[A-Z0-9][A-Z0-9._:-]{0,63}"))) {
            throw new IllegalArgumentException(field + " must be a stable bounded code");
        }
        return normalized;
    }

    private static String normalizeCode(String value, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 64 || !normalized.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be a stable bounded code");
        }
        return normalized;
    }

    private static String normalizeOptionalIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && (normalized.length() > 128
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }
}
