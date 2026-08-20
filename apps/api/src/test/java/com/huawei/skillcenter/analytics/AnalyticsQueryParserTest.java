package com.huawei.skillcenter.analytics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsQueryParserTest {
    private final AnalyticsQueryParser parser = new AnalyticsQueryParser();

    @Test
    void defaultRangeCoversSevenInclusiveBeijingDays() {
        AnalyticsQuery query = parser.parse(null, null, null, null, null, null,
                LocalDate.of(2026, 8, 17));

        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(query.to()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(query.zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void customRangeIsInclusiveAndRejectsMoreThanNinetyDays() {
        AnalyticsQuery query = parser.parse("custom", "2026-08-01", "2026-08-31",
                "eox-query", "network-team", "codex", LocalDate.of(2026, 8, 31));

        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(query.to()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(query.skillId()).isEqualTo("eox-query");
        assertThat(query.teamId()).isEqualTo("network-team");
        assertThat(query.clientType()).isEqualTo("codex");
        assertThatThrownBy(() -> parser.parse("custom", "2026-01-01", "2026-04-01", null, null, null,
                LocalDate.of(2026, 8, 17)))
                .isInstanceOf(InvalidAnalyticsQueryException.class);
    }

    @Test
    void rejectsDatesForFixedRangeAndReversedCustomRange() {
        assertThatThrownBy(() -> parser.parse("30d", "2026-08-01", null, null, null, null,
                LocalDate.of(2026, 8, 17)))
                .isInstanceOf(InvalidAnalyticsQueryException.class);
        assertThatThrownBy(() -> parser.parse("custom", "2026-08-18", "2026-08-17", null, null, null,
                LocalDate.of(2026, 8, 17)))
                .isInstanceOf(InvalidAnalyticsQueryException.class);
    }
}
