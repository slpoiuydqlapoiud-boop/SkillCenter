package com.huawei.skillcenter.governance;

import java.time.Instant;

public record ReviewTask(
        String reviewId,
        String packageId,
        String skillId,
        String version,
        String status,
        String submittedBy,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String reason
) {
}
