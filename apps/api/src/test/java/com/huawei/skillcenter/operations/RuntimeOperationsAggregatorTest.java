package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeOperationsAggregatorTest {
    private static final Instant NOW = Instant.parse("2026-08-21T02:00:00Z");

    @Test
    void aggregatesSourceSeparatedOutcomeLatencyErrorsAdoptionAndTrend() {
        RuntimeOperationsAggregator aggregator = new RuntimeOperationsAggregator(
                Clock.fixed(NOW, ZoneOffset.UTC));
        List<RuntimeSummary> events = List.of(
                event("2026-08-21T09:55:00+08:00", "1.0.0", "success", 40, null, "production"),
                event("2026-08-21T09:56:00+08:00", "1.0.0", "failure", 100, "TOOL_ERROR", "production"),
                event("2026-08-21T09:57:00+08:00", "1.1.0", "timeout", 500, "TIMEOUT", "mock"),
                event("2026-08-21T09:58:00+08:00", "1.1.0", "success", 80, null, "mock"));

        RuntimeOperationsSnapshot snapshot = aggregator.aggregate(
                new RuntimeOperationsQuery(RuntimeOperationsWindow.SIXTY_MINUTES, null, null, null, null, null),
                events);

        assertThat(snapshot.totals().total()).isEqualTo(4);
        assertThat(snapshot.totals().successes()).isEqualTo(2);
        assertThat(snapshot.totals().failures()).isEqualTo(1);
        assertThat(snapshot.totals().timeouts()).isEqualTo(1);
        assertThat(snapshot.totals().successRate()).isEqualTo(50.0);
        assertThat(snapshot.latency().p95Ms()).isEqualTo(500);
        assertThat(snapshot.errors()).extracting(RuntimeOperationsSnapshot.ErrorCount::errorCode)
                .containsExactly("TIMEOUT", "TOOL_ERROR");
        assertThat(snapshot.versionAdoption()).extracting(RuntimeOperationsSnapshot.VersionAdoption::version)
                .containsExactly("1.0.0", "1.1.0");
        assertThat(snapshot.sources()).extracting(RuntimeOperationsSnapshot.SourceBreakdown::dataSource)
                .containsExactly("mock", "production");
        assertThat(snapshot.trend()).isNotEmpty();
    }

    @Test
    void filtersBySkillVersionTeamAndDataSource() {
        RuntimeOperationsAggregator aggregator = new RuntimeOperationsAggregator(Clock.fixed(NOW, ZoneOffset.UTC));
        RuntimeSummary selected = event("2026-08-21T09:55:00+08:00", "1.0.0", "success", 40, null, "production");
        RuntimeSummary otherSkill = new RuntimeSummary(selected.schemaVersion(), UUID.randomUUID(), selected.occurredAt(),
                "skill-b", selected.version(), selected.status(), selected.durationMs(), selected.errorCode(),
                selected.dataSource(), "team-b", selected.clientType(), selected.traceRef());

        RuntimeOperationsSnapshot snapshot = aggregator.aggregate(
                new RuntimeOperationsQuery(RuntimeOperationsWindow.SIXTY_MINUTES, "skill-a", "1.0.0", "team-a", "production"),
                List.of(selected, otherSkill));

        assertThat(snapshot.totals().total()).isEqualTo(1);
        assertThat(snapshot.filters().skillId()).isEqualTo("skill-a");
        assertThat(snapshot.dataSource()).isEqualTo("production");
    }

    @Test
    void filtersRuntimeMetricsByExecutionEnvironment() {
        RuntimeOperationsAggregator aggregator = new RuntimeOperationsAggregator(Clock.fixed(NOW, ZoneOffset.UTC));
        RuntimeSummary selected = new RuntimeSummary("1.0", UUID.randomUUID(),
                OffsetDateTime.parse("2026-08-21T09:55:00+08:00"), "skill-a", "1.0.0", "success", 40,
                null, "mock", "team-a", "codex", null, "openclaw", "mcp-a", "llm-a");
        RuntimeSummary otherEnvironment = new RuntimeSummary("1.0", UUID.randomUUID(), selected.occurredAt(),
                "skill-a", "1.0.0", "failure", 400, "TOOL_ERROR", "mock", "team-a", "codex", null,
                "openclaw", "mcp-b", "llm-a");

        RuntimeOperationsSnapshot snapshot = aggregator.aggregate(
                new RuntimeOperationsQuery(RuntimeOperationsWindow.SIXTY_MINUTES, "skill-a", "1.0.0", null,
                        "mock", null, "openclaw", "mcp-a", "llm-a"),
                List.of(selected, otherEnvironment));

        assertThat(snapshot.totals().total()).isEqualTo(1);
        assertThat(snapshot.totals().successes()).isEqualTo(1);
        assertThat(snapshot.filters().mcpServerId()).isEqualTo("mcp-a");
    }

    @Test
    void returnsAnExplicitEmptyWindowAndHandlesAUsefulMvpVolume() {
        RuntimeOperationsAggregator aggregator = new RuntimeOperationsAggregator(Clock.fixed(NOW, ZoneOffset.UTC));
        RuntimeOperationsSnapshot empty = aggregator.aggregate(
                new RuntimeOperationsQuery(RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null, null, null, null), List.of());
        assertThat(empty.totals().total()).isZero();
        assertThat(empty.trend()).isNotEmpty();

        List<RuntimeSummary> events = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) {
            events.add(event("2026-08-21T09:00:00+08:00", index % 2 == 0 ? "1.0.0" : "1.1.0",
                    index % 10 == 0 ? "failure" : "success", 20 + index % 100,
                    index % 10 == 0 ? "TOOL_ERROR" : null, index % 3 == 0 ? "mock" : "production"));
        }
        long started = System.nanoTime();
        RuntimeOperationsSnapshot large = aggregator.aggregate(
                new RuntimeOperationsQuery(RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null, null, null, null), events);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(large.totals().total()).isEqualTo(10_000);
        assertThat(elapsedMs).as("MVP aggregation query should remain interactive").isLessThan(5_000);
    }

    private RuntimeSummary event(String occurredAt, String version, String status, long duration,
                                 String errorCode, String dataSource) {
        return new RuntimeSummary("1.0", UUID.randomUUID(), OffsetDateTime.parse(occurredAt),
                "skill-a", version, status, duration, errorCode, dataSource, "team-a", "codex", null);
    }
}
