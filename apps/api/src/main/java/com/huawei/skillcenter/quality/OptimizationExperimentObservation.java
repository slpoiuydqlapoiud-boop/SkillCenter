package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public record OptimizationExperimentObservation(
        String observationId,
        String experimentId,
        String skillId,
        String candidateVersion,
        String dataSource,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        String window,
        Instant capturedAt,
        String capturedBy,
        long totalCalls,
        long successfulCalls,
        long failures,
        long timeouts,
        long cancellations,
        double successRate,
        long p95Ms,
        String observationStatus
) {
    public OptimizationExperimentObservation {
        requireIdentifier(observationId, "observationId");
        requireIdentifier(experimentId, "experimentId");
        requireIdentifier(skillId, "skillId");
        requireText(candidateVersion, "candidateVersion", 128);
        dataSource = normalizeDataSource(dataSource);
        runtimeId = normalizeEnvironment(runtimeId, "runtimeId");
        mcpServerId = normalizeEnvironment(mcpServerId, "mcpServerId");
        llmProviderId = normalizeEnvironment(llmProviderId, "llmProviderId");
        window = normalizeWindow(window);
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
        requireIdentifier(capturedBy, "capturedBy");
        requireMetric(totalCalls, "totalCalls");
        requireMetric(successfulCalls, "successfulCalls");
        requireMetric(failures, "failures");
        requireMetric(timeouts, "timeouts");
        requireMetric(cancellations, "cancellations");
        long classifiedCalls;
        try {
            classifiedCalls = Math.addExact(Math.addExact(successfulCalls, failures),
                    Math.addExact(timeouts, cancellations));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("observation counters exceed supported range", overflow);
        }
        if (classifiedCalls > totalCalls) {
            throw new IllegalArgumentException("observation counters exceed totalCalls");
        }
        if (Double.isNaN(successRate) || Double.isInfinite(successRate) || successRate < 0 || successRate > 100) {
            throw new IllegalArgumentException("successRate must be between 0 and 100");
        }
        requireMetric(p95Ms, "p95Ms");
        observationStatus = normalizeStatus(observationStatus);
        if (totalCalls == 0 && !"NO_TRAFFIC".equals(observationStatus)) {
            throw new IllegalArgumentException("empty observation must have NO_TRAFFIC status");
        }
        if (totalCalls > 0 && !"CAPTURED".equals(observationStatus)) {
            throw new IllegalArgumentException("non-empty observation must have CAPTURED status");
        }
    }

    public static String normalizeWindow(String value) {
        String normalized = value == null || value.isBlank() ? "24h" : value.trim();
        return switch (normalized) {
            case "5m", "15m", "60m", "24h", "7d" -> normalized;
            default -> throw new IllegalArgumentException("window must be one of 5m, 15m, 60m, 24h or 7d");
        };
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

    private static String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CAPTURED", "NO_TRAFFIC").contains(normalized)) {
            throw new IllegalArgumentException("observationStatus must be CAPTURED or NO_TRAFFIC");
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
