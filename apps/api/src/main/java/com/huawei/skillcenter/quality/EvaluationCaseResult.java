package com.huawei.skillcenter.quality;

import java.time.Instant;

/** Redacted, queryable evidence for one evaluation case; no business payload is retained. */
public record EvaluationCaseResult(
        String runId,
        String caseId,
        String caseName,
        RunnerExecutionStatus executionStatus,
        boolean passed,
        int score,
        long durationMs,
        String errorCode,
        String reason,
        String dataSource,
        Instant evaluatedAt
) {
    public EvaluationCaseResult {
        require(runId, "runId");
        require(caseId, "caseId");
        caseName = normalize(caseName);
        executionStatus = executionStatus == null ? RunnerExecutionStatus.FAILED : executionStatus;
        errorCode = normalize(errorCode);
        reason = normalize(reason);
        dataSource = normalize(dataSource);
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be between 0 and 100");
        if (durationMs < 0) throw new IllegalArgumentException("durationMs must not be negative");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
