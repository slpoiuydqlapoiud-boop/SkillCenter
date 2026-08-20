package com.huawei.skillcenter.analytics;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

public class AnalyticsQueryParser {
    public static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final long MAX_CUSTOM_DAYS = 90;

    public AnalyticsQuery parse(String range, String from, String to,
                                String skillId, String teamId, String clientType,
                                LocalDate today) {
        ZoneId zoneId = BEIJING_ZONE;
        LocalDate effectiveToday = today == null ? LocalDate.now(zoneId) : today;
        String normalizedRange = normalize(range);
        if (normalizedRange == null) {
            normalizedRange = "7d";
        }

        AnalyticsQuery.RangeType rangeType;
        LocalDate start;
        LocalDate end;
        switch (normalizedRange.toLowerCase(Locale.ROOT)) {
            case "7d" -> {
                rangeType = AnalyticsQuery.RangeType.DAYS_7;
                start = effectiveToday.minusDays(6);
                end = effectiveToday;
                rejectFixedDates(from, to);
            }
            case "30d" -> {
                rangeType = AnalyticsQuery.RangeType.DAYS_30;
                start = effectiveToday.minusDays(29);
                end = effectiveToday;
                rejectFixedDates(from, to);
            }
            case "90d" -> {
                rangeType = AnalyticsQuery.RangeType.DAYS_90;
                start = effectiveToday.minusDays(89);
                end = effectiveToday;
                rejectFixedDates(from, to);
            }
            case "custom" -> {
                rangeType = AnalyticsQuery.RangeType.CUSTOM;
                start = parseDate(from, "from");
                end = parseDate(to, "to");
                long days = ChronoUnit.DAYS.between(start, end) + 1;
                if (days > MAX_CUSTOM_DAYS) {
                    throw new InvalidAnalyticsQueryException("Custom analytics range cannot exceed 90 days");
                }
            }
            default -> throw new InvalidAnalyticsQueryException("range must be one of 7d, 30d, 90d, custom");
        }

        return new AnalyticsQuery(rangeType, start, end,
                normalize(skillId), normalize(teamId), normalize(clientType), zoneId);
    }

    private void rejectFixedDates(String from, String to) {
        if (normalize(from) != null || normalize(to) != null) {
            throw new InvalidAnalyticsQueryException("from and to are only allowed for custom range");
        }
    }

    private LocalDate parseDate(String value, String name) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new InvalidAnalyticsQueryException(name + " is required for custom range");
        }
        try {
            return LocalDate.parse(normalized, DATE_FORMAT);
        } catch (DateTimeException exception) {
            throw new InvalidAnalyticsQueryException(name + " must use YYYY-MM-DD", exception);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
