package com.huawei.skillcenter.governance;

import java.time.OffsetDateTime;

public record ExportFilters(
        OffsetDateTime from,
        OffsetDateTime to,
        String skillId,
        String teamId,
        String clientType,
        String status
) {
    public ExportFilters {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        skillId = normalize(skillId);
        teamId = normalize(teamId);
        clientType = normalize(clientType);
        status = normalize(status);
    }

    public static ExportFilters empty() {
        return new ExportFilters(null, null, null, null, null, null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
