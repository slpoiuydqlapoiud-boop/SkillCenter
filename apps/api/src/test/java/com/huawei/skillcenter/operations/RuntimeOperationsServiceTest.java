package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.events.InvocationEventService;
import com.huawei.skillcenter.quality.ObservabilityProvider;
import com.huawei.skillcenter.quality.ProviderHealth;
import com.huawei.skillcenter.quality.RunnerExecutionStatus;
import com.huawei.skillcenter.quality.RunnerExecutionSummary;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeOperationsServiceTest {
    @Test
    void keepsMockSummaryEventIdsStableWhenProviderOrderChanges() {
        MutableObservabilityProvider provider = new MutableObservabilityProvider(List.of(
                summary("run-a", "1.0.0"),
                summary("run-b", "1.1.0")));
        RuntimeOperationsService service = new RuntimeOperationsService(
                new RuntimeSummaryService(), new InvocationEventService(), provider,
                Clock.fixed(Instant.parse("2026-08-24T02:00:00Z"), ZoneOffset.UTC));

        Map<String, java.util.UUID> first = idsByRun(service.summaries());
        provider.replace(List.of(summary("run-b", "1.1.0"), summary("run-a", "1.0.0")));

        Map<String, java.util.UUID> reordered = idsByRun(service.summaries());

        assertThat(reordered).containsExactlyInAnyOrderEntriesOf(first);
    }

    @Test
    void usesInjectedClockWhenMockSummaryHasNoOccurrenceTime() {
        Instant fixedNow = Instant.parse("2026-08-24T02:00:00Z");
        MutableObservabilityProvider provider = new MutableObservabilityProvider(List.of(
                new RunnerExecutionSummary("run-without-time", "skill-a", "1.0.0",
                        RunnerExecutionStatus.SUCCEEDED, 42, null, "mock", null, "mock-runtime", "", "")));
        RuntimeOperationsService service = new RuntimeOperationsService(
                new RuntimeSummaryService(), new InvocationEventService(), provider,
                Clock.fixed(fixedNow, ZoneOffset.UTC));

        assertThat(service.summaries()).singleElement()
                .extracting(RuntimeSummary::occurredAt)
                .isEqualTo(java.time.OffsetDateTime.ofInstant(fixedNow, ZoneOffset.UTC));
    }

    private Map<String, java.util.UUID> idsByRun(List<RuntimeSummary> summaries) {
        return summaries.stream().collect(Collectors.toMap(RuntimeSummary::traceRef,
                RuntimeSummary::eventId));
    }

    private RunnerExecutionSummary summary(String runId, String version) {
        return new RunnerExecutionSummary(runId, "skill-a", version, RunnerExecutionStatus.SUCCEEDED,
                42, null, "mock", Instant.parse("2026-08-24T01:59:00Z"), "mock-runtime", "", "");
    }

    private static final class MutableObservabilityProvider implements ObservabilityProvider {
        private List<RunnerExecutionSummary> values;

        private MutableObservabilityProvider(List<RunnerExecutionSummary> values) {
            this.values = new ArrayList<>(values);
        }

        private void replace(List<RunnerExecutionSummary> next) {
            values = new ArrayList<>(next);
        }

        @Override
        public String providerId() {
            return "test-observability";
        }

        @Override
        public String providerVersion() {
            return "1.0";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.up();
        }

        @Override
        public List<RunnerExecutionSummary> summaries() {
            return List.copyOf(values);
        }

        @Override
        public void record(RunnerExecutionSummary summary) {
            values.add(summary);
        }
    }
}
