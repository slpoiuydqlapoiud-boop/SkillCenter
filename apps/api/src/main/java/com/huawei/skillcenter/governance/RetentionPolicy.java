package com.huawei.skillcenter.governance;

import java.time.OffsetDateTime;

public record RetentionPolicy(
        long policyVersion,
        int auditRetentionDays,
        int invocationRetentionDays,
        int installationRetentionDays,
        String updatedBy,
        OffsetDateTime updatedAt
) {
    public static final int DEFAULT_AUDIT_RETENTION_DAYS = 365;
    public static final int DEFAULT_INVOCATION_RETENTION_DAYS = 90;
    public static final int DEFAULT_INSTALLATION_RETENTION_DAYS = 90;
    public static final int MIN_AUDIT_RETENTION_DAYS = 365;
    public static final int MIN_INVOCATION_RETENTION_DAYS = 30;
    public static final int MIN_INSTALLATION_RETENTION_DAYS = 30;

    public RetentionPolicy {
        if (policyVersion <= 0) {
            policyVersion = 1;
        }
        if (auditRetentionDays == 0) {
            auditRetentionDays = DEFAULT_AUDIT_RETENTION_DAYS;
        }
        if (invocationRetentionDays == 0) {
            invocationRetentionDays = DEFAULT_INVOCATION_RETENTION_DAYS;
        }
        if (installationRetentionDays == 0) {
            installationRetentionDays = DEFAULT_INSTALLATION_RETENTION_DAYS;
        }
        if (auditRetentionDays < MIN_AUDIT_RETENTION_DAYS) {
            throw new IllegalArgumentException("auditRetentionDays is below the safety floor");
        }
        if (invocationRetentionDays < MIN_INVOCATION_RETENTION_DAYS) {
            throw new IllegalArgumentException("invocationRetentionDays is below the safety floor");
        }
        if (installationRetentionDays < MIN_INSTALLATION_RETENTION_DAYS) {
            throw new IllegalArgumentException("installationRetentionDays is below the safety floor");
        }
        updatedBy = updatedBy == null || updatedBy.isBlank() ? null : updatedBy.trim();
    }

    public static RetentionPolicy defaults() {
        return new RetentionPolicy(1, DEFAULT_AUDIT_RETENTION_DAYS,
                DEFAULT_INVOCATION_RETENTION_DAYS, DEFAULT_INSTALLATION_RETENTION_DAYS,
                null, null);
    }
}
