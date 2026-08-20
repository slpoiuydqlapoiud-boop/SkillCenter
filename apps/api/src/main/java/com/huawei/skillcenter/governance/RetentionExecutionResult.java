package com.huawei.skillcenter.governance;

public record RetentionExecutionResult(String executionId, String previewId, long policyVersion,
                                       long invocationDeleted, long installationDeleted,
                                       long auditArchiveEligibleCount, boolean idempotent) {
}
