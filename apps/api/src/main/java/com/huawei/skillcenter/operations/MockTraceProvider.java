package com.huawei.skillcenter.operations;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(name = "skill-center.providers.trace", havingValue = "mock", matchIfMissing = true)
public class MockTraceProvider implements TraceProvider {
    private final RuntimeSummaryService runtimeSummaryService;

    public MockTraceProvider(RuntimeSummaryService runtimeSummaryService) {
        this.runtimeSummaryService = runtimeSummaryService;
    }

    @Override
    public String providerId() {
        return "mock-trace";
    }

    @Override
    public String providerVersion() {
        return "1.0";
    }

    @Override
    public List<TraceObservation> query(TraceQuery query) {
        InstantWindow window = new InstantWindow(query.effectiveNow().minusSeconds(query.window().seconds()));
        return runtimeSummaryService.events().stream()
                .filter(summary -> summary.traceRef() != null && !summary.traceRef().isBlank())
                .filter(summary -> summary.occurredAt().toInstant().isAfter(window.cutoff()))
                .filter(summary -> query.skillId() == null || query.skillId().equals(summary.skillId()))
                .filter(summary -> query.version() == null || query.version().equals(summary.version()))
                .filter(summary -> query.traceId() == null || query.traceId().equals(summary.traceRef()))
                .filter(summary -> query.status() == null || query.status().equals(summary.status()))
                .filter(summary -> query.dataSource() == null || "all".equals(query.dataSource())
                        || query.dataSource().equals(summary.dataSource()))
                .filter(summary -> query.runtimeId() == null || query.runtimeId().equals(summary.runtimeId()))
                .filter(summary -> query.mcpServerId() == null || query.mcpServerId().equals(summary.mcpServerId()))
                .filter(summary -> query.llmProviderId() == null || query.llmProviderId().equals(summary.llmProviderId()))
                .map(summary -> new TraceObservation(summary.traceRef(), summary.eventId().toString(),
                        summary.skillId(), summary.version(), "skill.run", summary.status(), summary.durationMs(),
                        summary.errorCode(), summary.dataSource(), summary.occurredAt(), summary.runtimeId(),
                        summary.mcpServerId(), summary.llmProviderId()))
                .sorted(Comparator.comparing(TraceObservation::occurredAt).reversed()
                        .thenComparing(TraceObservation::spanId))
                .toList();
    }

    private record InstantWindow(java.time.Instant cutoff) {
    }
}
