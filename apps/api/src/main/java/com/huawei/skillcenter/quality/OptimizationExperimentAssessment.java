package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public record OptimizationExperimentAssessment(
        String assessmentId,
        String experimentId,
        String workItemId,
        String skillId,
        String sourceVersion,
        String candidateVersion,
        String dataSource,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        String suiteId,
        String suiteVersion,
        String window,
        String observationId,
        Metrics candidate,
        Metrics baseline,
        double minSuccessRatePercent,
        long maxP95Ms,
        long minRuntimeSamples,
        String conclusion,
        String reasonCode,
        String recommendedAction,
        String action,
        String note,
        String assessedBy,
        Instant assessedAt
) {
    public static final String HEALTHY = "HEALTHY";
    public static final String REGRESSION = "REGRESSION";
    public static final String INSUFFICIENT_TRAFFIC = "INSUFFICIENT_TRAFFIC";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";
    public static final String KEEP = "KEEP";
    public static final String CREATE_FOLLOW_UP = "CREATE_FOLLOW_UP";
    public static final String ROLLBACK_REVIEW = "ROLLBACK_REVIEW";
    public static final String CONTINUE_OBSERVING = "CONTINUE_OBSERVING";

    private static final Set<String> CONCLUSIONS = Set.of(HEALTHY, REGRESSION, INSUFFICIENT_TRAFFIC, INCONCLUSIVE);
    private static final Set<String> RECOMMENDATIONS = Set.of(KEEP, CREATE_FOLLOW_UP, ROLLBACK_REVIEW, CONTINUE_OBSERVING);
    private static final Set<String> ACTIONS = Set.of(KEEP, CREATE_FOLLOW_UP, ROLLBACK_REVIEW);

    public OptimizationExperimentAssessment(String assessmentId, String experimentId, String workItemId,
                                             String skillId, String sourceVersion, String candidateVersion,
                                             String dataSource, String runtimeId, String mcpServerId,
                                             String llmProviderId, String window, String observationId,
                                             Metrics candidate, Metrics baseline, double minSuccessRatePercent,
                                             long maxP95Ms, long minRuntimeSamples, String conclusion,
                                             String reasonCode, String recommendedAction, String action,
                                             String note, String assessedBy, Instant assessedAt) {
        this(assessmentId, experimentId, workItemId, skillId, sourceVersion, candidateVersion, dataSource,
                runtimeId, mcpServerId, llmProviderId, "", "", window, observationId, candidate, baseline,
                minSuccessRatePercent, maxP95Ms, minRuntimeSamples, conclusion, reasonCode, recommendedAction,
                action, note, assessedBy, assessedAt);
    }

    public OptimizationExperimentAssessment {
        requireIdentifier(assessmentId, "assessmentId");
        requireIdentifier(experimentId, "experimentId");
        requireIdentifier(workItemId, "workItemId");
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
        window = normalizeWindow(window);
        requireIdentifier(observationId, "observationId");
        if (candidate == null || baseline == null) throw new IllegalArgumentException("assessment metrics are required");
        if (!Double.isFinite(minSuccessRatePercent) || minSuccessRatePercent < 0 || minSuccessRatePercent > 100) {
            throw new IllegalArgumentException("minSuccessRatePercent must be between 0 and 100");
        }
        if (maxP95Ms < 1 || maxP95Ms > 600_000) throw new IllegalArgumentException("maxP95Ms is out of range");
        if (minRuntimeSamples < 1 || minRuntimeSamples > 1_000_000) {
            throw new IllegalArgumentException("minRuntimeSamples is out of range");
        }
        conclusion = normalizeCode(conclusion, "conclusion");
        if (!CONCLUSIONS.contains(conclusion)) throw new IllegalArgumentException("conclusion is not supported");
        reasonCode = normalizeCode(reasonCode, "reasonCode");
        recommendedAction = normalizeCode(recommendedAction, "recommendedAction");
        if (!RECOMMENDATIONS.contains(recommendedAction)) throw new IllegalArgumentException("recommendedAction is not supported");
        action = normalizeCode(action, "action");
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("action is not supported");
        note = note == null ? "" : note.trim();
        if (note.length() > 512) throw new IllegalArgumentException("note exceeds maximum length");
        requireIdentifier(assessedBy, "assessedBy");
        assessedAt = assessedAt == null ? Instant.now() : assessedAt;
    }

    public record Metrics(long totalCalls, long successfulCalls, long failures, long timeouts,
                          long cancellations, double successRate, long p95Ms, Instant measuredAt) {
        public Metrics {
            requireMetric(totalCalls, "totalCalls");
            requireMetric(successfulCalls, "successfulCalls");
            requireMetric(failures, "failures");
            requireMetric(timeouts, "timeouts");
            requireMetric(cancellations, "cancellations");
            long classified;
            try {
                classified = Math.addExact(Math.addExact(successfulCalls, failures),
                        Math.addExact(timeouts, cancellations));
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("assessment counters exceed supported range", overflow);
            }
            if (classified > totalCalls) throw new IllegalArgumentException("assessment counters exceed totalCalls");
            if (!Double.isFinite(successRate) || successRate < 0 || successRate > 100) {
                throw new IllegalArgumentException("successRate must be between 0 and 100");
            }
            requireMetric(p95Ms, "p95Ms");
            measuredAt = measuredAt == null ? Instant.now() : measuredAt;
        }
    }

    private static String normalizeWindow(String value) {
        return OptimizationExperimentObservation.normalizeWindow(value);
    }

    private static String normalizeDataSource(String value) {
        String normalized = value == null || value.isBlank() ? "all" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("mock", "production", "all").contains(normalized)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
        return normalized;
    }

    private static String normalizeEnvironment(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 128 || (!normalized.isBlank()
                && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String normalizeOptionalIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 128) throw new IllegalArgumentException(field + " exceeds maximum length");
        if (!normalized.isBlank() && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String normalizeCode(String value, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 64
                || !normalized.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be a stable bounded code");
        }
        return normalized;
    }

    private static void requireMetric(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
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
