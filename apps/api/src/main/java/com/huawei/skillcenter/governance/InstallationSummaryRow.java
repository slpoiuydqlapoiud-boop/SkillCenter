package com.huawei.skillcenter.governance;

import java.time.LocalDate;

public record InstallationSummaryRow(
        LocalDate date,
        String skillId,
        String version,
        String status,
        String clientType,
        String clientVersion,
        long count
) {
}
