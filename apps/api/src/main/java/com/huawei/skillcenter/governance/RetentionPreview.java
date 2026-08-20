package com.huawei.skillcenter.governance;

import java.time.Instant;
import java.time.OffsetDateTime;

public record RetentionPreview(
        String previewId,
        long policyVersion,
        OffsetDateTime expiresAt,
        Instant invocationCutoff,
        Instant installationCutoff,
        Instant auditCutoff,
        long invocationEligibleCount,
        long installationEligibleCount,
        long auditArchiveEligibleCount,
        long estimatedBytes
) {
}
