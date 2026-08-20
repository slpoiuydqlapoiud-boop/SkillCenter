package com.huawei.skillcenter.governance;

import java.time.LocalDate;

public record InvocationSummaryRow(
        LocalDate date,
        String skillId,
        String version,
        String status,
        String clientType,
        String clientVersion,
        long count,
        long avgDurationMs,
        long p95DurationMs,
        long errorCount
) {
}
