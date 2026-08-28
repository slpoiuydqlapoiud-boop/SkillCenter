package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TraceServiceTest {
    @Test
    void returnsOnlyRedactedTraceMetadataForMatchingFailure() {
        RuntimeSummaryService summaries = new RuntimeSummaryService();
        summaries.ingest(new RuntimeSummary("1.0", UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC),
                "skill-a", "1.0.0", "failure", 124, "DEPENDENCY_TIMEOUT", "production", "team-a", "codex", "trace-a"));
        summaries.ingest(new RuntimeSummary("1.0", UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC),
                "skill-a", "1.0.0", "success", 20, null, "production", "team-a", "codex", "trace-b"));

        TraceService service = new TraceService(new MockTraceProvider(summaries));
        List<TraceObservation> result = service.query(new TraceQuery(RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                "skill-a", "1.0.0", null, "failure", "production"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).traceId()).isEqualTo("trace-a");
        assertThat(result.get(0).errorCode()).isEqualTo("DEPENDENCY_TIMEOUT");
        assertThat(result.get(0).operation()).isEqualTo("skill.run");
    }

    @Test
    void rejectsTraceQueryWithUnsupportedStatusOrSource() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TraceQuery(
                RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null, null, null, "running", "production"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TraceQuery(
                RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null, null, null, "failure", "external"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtersRedactedTraceMetadataByExecutionEnvironment() {
        RuntimeSummaryService summaries = new RuntimeSummaryService();
        summaries.ingest(new RuntimeSummary("1.0", UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC),
                "skill-a", "1.0.0", "failure", 124, "DEPENDENCY_TIMEOUT", "production", "team-a", "codex",
                "trace-a", "openclaw", "mcp-a", "llm-a"));
        summaries.ingest(new RuntimeSummary("1.0", UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC),
                "skill-a", "1.0.0", "failure", 200, "DEPENDENCY_TIMEOUT", "production", "team-a", "codex",
                "trace-b", "openclaw", "mcp-b", "llm-a"));

        TraceService service = new TraceService(new MockTraceProvider(summaries));
        List<TraceObservation> result = service.query(new TraceQuery(RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                "skill-a", "1.0.0", null, "failure", "production", "openclaw", "mcp-a", "llm-a"));

        assertThat(result).extracting(TraceObservation::traceId).containsExactly("trace-a");
    }

    @Test
    void keepsSameSpanIdWhenItBelongsToDifferentTraces() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TraceObservation first = new TraceObservation("trace-a", "span-1", "skill-a", "1.0.0",
                "skill.run", "failure", 10, "ERR_A", "production", now);
        TraceObservation second = new TraceObservation("trace-b", "span-1", "skill-a", "1.0.0",
                "skill.run", "failure", 20, "ERR_B", "production", now.minusSeconds(1));
        TraceProvider provider = new TraceProvider() {
            @Override public String providerId() { return "test-trace"; }
            @Override public String providerVersion() { return "1.0"; }
            @Override public List<TraceObservation> query(TraceQuery query) { return List.of(first, second); }
        };

        List<TraceObservation> result = new TraceService(provider).query(new TraceQuery(
                RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null, null, null, "failure", "production"));

        assertThat(result).extracting(TraceObservation::traceId).containsExactly("trace-a", "trace-b");
    }
}
