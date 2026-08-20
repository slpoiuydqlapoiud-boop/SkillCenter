package com.huawei.skillcenter.analytics;

import com.huawei.skillcenter.events.InvocationEvent;
import com.huawei.skillcenter.events.InvocationIngestionStats;
import com.huawei.skillcenter.governance.InstallationRecord;
import com.huawei.skillcenter.skill.SkillMetrics;
import com.huawei.skillcenter.skill.SkillSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsAggregatorTest {
    private final AnalyticsAggregator aggregator = new AnalyticsAggregator();
    private final AnalyticsQueryParser queryParser = new AnalyticsQueryParser();

    @Test
    void aggregateUsesBeijingDayAndCalculatesExtendedMetrics() {
        AnalyticsOverview result = aggregator.aggregate(
                queryParser.parse("custom", "2026-08-16", "2026-08-17", null, null, null,
                        LocalDate.of(2026, 8, 17)),
                skills(), List.of(
                        event("2026-08-15T16:30:00Z", "eox-query", "1.2.0", "u1", "team-a", "success", 100, null),
                        event("2026-08-16T16:30:00Z", "eox-query", "1.2.0", "u2", "team-a", "success", 300, null),
                        event("2026-08-17T01:00:00Z", "eox-query", "1.1.0", "u1", "team-b", "timeout", 900, "UPSTREAM_TIMEOUT")),
                installations(), ingestionStats());

        assertThat(result.series()).extracting(AnalyticsOverview.DayPoint::calls).containsExactly(1L, 2L);
        assertThat(result.kpis().activeUsers()).isEqualTo(2);
        assertThat(result.kpis().activeTeams()).isEqualTo(2);
        assertThat(result.kpis().successRate()).isEqualTo(66.67);
        assertThat(result.kpis().currentInstallations()).isEqualTo(1);
        assertThat(result.latency().p50Ms()).isEqualTo(300);
        assertThat(result.latency().p95Ms()).isEqualTo(900);
        assertThat(result.errorBreakdown().get(0).errorCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(result.versionAdoption()).extracting(AnalyticsOverview.VersionAdoption::version)
                .containsExactly("1.2.0", "1.1.0");
    }

    @Test
    void emptyRangeIsZeroSafeAndKeepsEveryDay() {
        AnalyticsOverview result = aggregator.aggregate(
                queryParser.parse("custom", "2026-08-01", "2026-08-03", null, null, null,
                        LocalDate.of(2026, 8, 3)),
                skills(), List.of(), installations(), new InvocationIngestionStats(List.of()));

        assertThat(result.series()).hasSize(3)
                .allSatisfy(point -> assertThat(point.calls()).isZero());
        assertThat(result.kpis().successRate()).isZero();
        assertThat(result.latency().p50Ms()).isZero();
        assertThat(result.latency().p95Ms()).isZero();
    }

    private List<SkillSummary> skills() {
        return List.of(new SkillSummary("eox-query", "EOX 查询", "1.2.0", "desc", "效率",
                List.of(), "low", "low", "team-a", "owner-a", "magnifying-glass", "blue", "published",
                "2026-08-01", new SkillMetrics(4.8, 20, 0, 1, 2)));
    }

    private List<InstallationRecord> installations() {
        return List.of(new InstallationRecord("install-1", "manifest-1", "eox-query", "1.2.0",
                "codex", "1.0.0", "u1", "installed", Instant.parse("2026-08-16T01:00:00Z"),
                Instant.parse("2026-08-16T01:01:00Z"), "team-a", "device-1234567890123456", "one-click",
                "event-1", null, Instant.parse("2026-08-16T01:01:00Z"), null));
    }

    private InvocationIngestionStats ingestionStats() {
        return new InvocationIngestionStats(List.of(
                new InvocationIngestionStats.Entry(UUID.randomUUID(), OffsetDateTime.parse("2026-08-15T16:30:00Z"),
                        Instant.parse("2026-08-15T16:31:00Z"), InvocationIngestionStats.Result.ACCEPTED),
                new InvocationIngestionStats.Entry(UUID.randomUUID(), OffsetDateTime.parse("2026-08-16T16:30:00Z"),
                        Instant.parse("2026-08-16T16:31:00Z"), InvocationIngestionStats.Result.ACCEPTED),
                new InvocationIngestionStats.Entry(UUID.randomUUID(), OffsetDateTime.parse("2026-08-17T01:00:00Z"),
                        Instant.parse("2026-08-17T01:01:00Z"), InvocationIngestionStats.Result.ACCEPTED)));
    }

    private InvocationEvent event(String occurredAt, String skillId, String version, String userId,
                                  String teamId, String status, long durationMs, String errorCode) {
        return new InvocationEvent("1.0", UUID.randomUUID(), OffsetDateTime.parse(occurredAt), skillId, version,
                new InvocationEvent.Subject(userId, teamId), new InvocationEvent.Client("codex", "1.0.0"),
                "session_1234567890", status, durationMs, errorCode, null);
    }
}
