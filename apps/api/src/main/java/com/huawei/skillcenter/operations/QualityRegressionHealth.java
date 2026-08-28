package com.huawei.skillcenter.operations;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record QualityRegressionHealth(
        String status,
        String reasonCode,
        Instant checkedAt,
        int assessedSkillCount,
        int regressionCount,
        List<Regression> regressions) {
    private static final Set<String> STATUSES = Set.of("HEALTHY", "DEGRADED", "NOT_READY");

    public QualityRegressionHealth {
        status = normalize(status, "status");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("status is not supported");
        reasonCode = normalize(reasonCode, "reasonCode");
        if (checkedAt == null) checkedAt = Instant.now();
        if (assessedSkillCount < 0 || regressionCount < 0) {
            throw new IllegalArgumentException("quality regression counts must not be negative");
        }
        regressions = List.copyOf(regressions == null ? List.of() : regressions);
        if (regressions.size() > 100) throw new IllegalArgumentException("regressions exceed maximum size");
        if (regressionCount != regressions.size() && regressionCount <= 100) {
            throw new IllegalArgumentException("regressionCount must match regressions when bounded");
        }
    }

    public record Regression(String skillId, String sourceVersion, String candidateVersion,
                             String assessmentId, String conclusion, String reasonCode) {
        public Regression {
            skillId = required(skillId, "skillId");
            sourceVersion = required(sourceVersion, "sourceVersion");
            candidateVersion = required(candidateVersion, "candidateVersion");
            assessmentId = required(assessmentId, "assessmentId");
            conclusion = required(conclusion, "conclusion");
            reasonCode = required(reasonCode, "reasonCode");
        }
    }

    public static QualityRegressionHealth healthy(Instant checkedAt, int assessedSkillCount) {
        return new QualityRegressionHealth("HEALTHY", "QUALITY_REGRESSIONS_HEALTHY", checkedAt,
                assessedSkillCount, 0, List.of());
    }

    public static QualityRegressionHealth unavailable(Instant checkedAt) {
        return new QualityRegressionHealth("NOT_READY", "QUALITY_REGRESSION_SIGNAL_UNAVAILABLE", checkedAt,
                0, 0, List.of());
    }

    private static String normalize(String value, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.isBlank() || normalized.length() > 64) {
            throw new IllegalArgumentException(field + " must be a bounded code");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 128) {
            throw new IllegalArgumentException(field + " must be a bounded value");
        }
        return normalized;
    }
}
