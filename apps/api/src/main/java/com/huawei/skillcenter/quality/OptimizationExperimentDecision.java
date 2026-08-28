package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public record OptimizationExperimentDecision(
        String decisionId,
        String experimentId,
        String skillId,
        String sourceVersion,
        String candidateVersion,
        String dataSource,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        String suiteId,
        String suiteVersion,
        String qualitySnapshotId,
        String benchmarkId,
        QualityGateStatus gateStatus,
        String benchmarkConclusion,
        String decision,
        String reasonCode,
        String reason,
        String recommendedAction,
        String evaluatedBy,
        Instant evaluatedAt
) {
    public static final String PROMOTE_CANDIDATE = "PROMOTE_CANDIDATE";
    public static final String ITERATE = "ITERATE";
    public static final String REJECT_CANDIDATE = "REJECT_CANDIDATE";
    public static final String NOT_COMPARABLE = "NOT_COMPARABLE";
    private static final Set<String> DECISIONS = Set.of(PROMOTE_CANDIDATE, ITERATE, REJECT_CANDIDATE, NOT_COMPARABLE);

    public OptimizationExperimentDecision {
        requireIdentifier(decisionId, "decisionId");
        requireIdentifier(experimentId, "experimentId");
        requireIdentifier(skillId, "skillId");
        requireText(sourceVersion, "sourceVersion", 128);
        requireText(candidateVersion, "candidateVersion", 128);
        dataSource = normalizeDataSource(dataSource);
        runtimeId = normalizeEnvironment(runtimeId, "runtimeId");
        mcpServerId = normalizeEnvironment(mcpServerId, "mcpServerId");
        llmProviderId = normalizeEnvironment(llmProviderId, "llmProviderId");
        suiteId = normalizeOptionalIdentifier(suiteId, "suiteId");
        suiteVersion = normalizeOptionalIdentifier(suiteVersion, "suiteVersion");
        if (suiteId.isBlank() != suiteVersion.isBlank()) {
            throw new IllegalArgumentException("suiteId and suiteVersion must be provided together");
        }
        qualitySnapshotId = normalizeRequiredIdentifier(qualitySnapshotId, "qualitySnapshotId");
        benchmarkId = normalizeRequiredIdentifier(benchmarkId, "benchmarkId");
        if (gateStatus == null) throw new IllegalArgumentException("gateStatus must not be null");
        benchmarkConclusion = normalizeCode(benchmarkConclusion, "benchmarkConclusion", 64);
        decision = normalizeCode(decision, "decision", 64);
        if (!DECISIONS.contains(decision)) throw new IllegalArgumentException("decision is not supported");
        reasonCode = normalizeCode(reasonCode, "reasonCode", 64);
        reason = boundedText(reason, "reason", 512);
        recommendedAction = boundedText(recommendedAction, "recommendedAction", 512);
        requireIdentifier(evaluatedBy, "evaluatedBy");
        evaluatedAt = evaluatedAt == null ? Instant.now() : evaluatedAt;
    }

    private static String normalizeDataSource(String value) {
        String normalized = value == null || value.isBlank() ? "all" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("mock", "production", "all").contains(normalized)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
        return normalized;
    }

    private static String normalizeEnvironment(String value, String field) {
        String normalized = normalizeOptionalIdentifier(value, field);
        if (!normalized.isBlank() && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String normalizeOptionalIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 128) throw new IllegalArgumentException(field + " exceeds maximum length");
        if (!normalized.isBlank()) requireIdentifier(normalized, field);
        return normalized;
    }

    private static String normalizeRequiredIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        requireIdentifier(normalized, field);
        return normalized;
    }

    private static String normalizeCode(String value, String field, int max) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > max || normalized.isBlank()
                || !normalized.matches("[A-Z0-9][A-Z0-9._:-]{0," + (max - 1) + "}")) {
            throw new IllegalArgumentException(field + " must be a stable bounded code");
        }
        return normalized;
    }

    private static String boundedText(String value, String field, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(field + " exceeds maximum length");
        return normalized;
    }

    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        if (value.trim().length() > max) throw new IllegalArgumentException(field + " exceeds maximum length");
    }

    private static void requireIdentifier(String value, String field) {
        requireText(value, field, 128);
        if (!value.trim().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }
}
