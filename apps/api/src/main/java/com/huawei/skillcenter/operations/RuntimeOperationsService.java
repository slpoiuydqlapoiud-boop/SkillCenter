package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.events.InvocationEvent;
import com.huawei.skillcenter.events.InvocationEventService;
import com.huawei.skillcenter.quality.ObservabilityProvider;
import com.huawei.skillcenter.quality.RunnerExecutionStatus;
import com.huawei.skillcenter.quality.RunnerExecutionSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Service
public class RuntimeOperationsService {
    private final RuntimeSummaryService summaryService;
    private final InvocationEventService invocationEventService;
    private final ObservabilityProvider observabilityProvider;
    private final RuntimeOperationsAggregator aggregator;
    private final Clock clock;

    @Autowired
    public RuntimeOperationsService(RuntimeSummaryService summaryService,
                                    InvocationEventService invocationEventService,
                                    ObservabilityProvider observabilityProvider) {
        this(summaryService, invocationEventService, observabilityProvider, Clock.systemUTC());
    }

    RuntimeOperationsService(RuntimeSummaryService summaryService,
                              InvocationEventService invocationEventService,
                              ObservabilityProvider observabilityProvider,
                              Clock clock) {
        this.summaryService = summaryService;
        this.invocationEventService = invocationEventService;
        this.observabilityProvider = observabilityProvider;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.aggregator = new RuntimeOperationsAggregator(this.clock);
    }

    public RuntimeOperationsSnapshot snapshot(RuntimeOperationsQuery query) {
        return aggregator.aggregate(query, allSummaries());
    }

    public List<RuntimeSummary> summaries() {
        return List.copyOf(allSummaries());
    }

    private List<RuntimeSummary> allSummaries() {
        LinkedHashMap<UUID, RuntimeSummary> values = new LinkedHashMap<>();
        summaryService.events().forEach(event -> values.put(event.eventId(), event));
        invocationEventService.events().stream().map(this::fromInvocation).forEach(event -> values.put(event.eventId(), event));
        for (RunnerExecutionSummary summary : observabilityProvider.summaries()) {
            RuntimeSummary event = fromMock(summary);
            values.put(event.eventId(), event);
        }
        return new ArrayList<>(values.values());
    }

    private RuntimeSummary fromInvocation(InvocationEvent event) {
        return new RuntimeSummary("1.0", event.eventId(), event.occurredAt(), event.skillId(), event.version(),
                event.status(), event.durationMs(), event.errorCode(), "production",
                event.subject() == null ? null : event.subject().teamId(),
                event.client() == null ? null : event.client().type(), event.eventId().toString());
    }

    private RuntimeSummary fromMock(RunnerExecutionSummary summary) {
        String status = switch (summary.status()) {
            case SUCCEEDED -> "success";
            case TIMED_OUT -> "timeout";
            case CANCELLED -> "cancelled";
            default -> "failure";
        };
        Instant occurredAt = summary.occurredAt() == null ? clock.instant() : summary.occurredAt();
        // The run ID is the stable identity of a Runner execution. Do not use the
        // provider list position here: a new execution or a reordered provider
        // response must not change historical Trace/event IDs.
        String stableIdentity = summary.runId();
        if (stableIdentity == null || stableIdentity.isBlank()) {
            stableIdentity = summary.skillId() + "|" + summary.skillVersion() + "|" + occurredAt;
        }
        UUID eventId = UUID.nameUUIDFromBytes(stableIdentity.getBytes(StandardCharsets.UTF_8));
        return new RuntimeSummary("1.0", eventId, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC),
                summary.skillId(), summary.skillVersion(), status, summary.durationMs(),
                "success".equals(status) || "cancelled".equals(status) ? null : summary.errorCode(),
                summary.dataSource(), null, "mock-runner", summary.runId(), summary.runtimeId(),
                summary.mcpServerId(), summary.llmProviderId());
    }
}
