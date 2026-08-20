package com.huawei.skillcenter.analytics;

import java.time.LocalDate;
import java.time.ZoneId;

public record AnalyticsQuery(
        RangeType rangeType,
        LocalDate from,
        LocalDate to,
        String skillId,
        String teamId,
        String clientType,
        ZoneId zoneId
) {
    public enum RangeType {
        DAYS_7,
        DAYS_30,
        DAYS_90,
        CUSTOM
    }

    public AnalyticsQuery {
        if (rangeType == null || from == null || to == null || zoneId == null) {
            throw new InvalidAnalyticsQueryException("Analytics range is incomplete");
        }
        if (to.isBefore(from)) {
            throw new InvalidAnalyticsQueryException("Analytics range is reversed");
        }
    }
}
