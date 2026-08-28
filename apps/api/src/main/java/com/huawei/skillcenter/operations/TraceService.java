package com.huawei.skillcenter.operations;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TraceService {
    private final TraceProvider provider;
    private final RuntimeOperationsService runtimeOperationsService;

    public TraceService(TraceProvider provider) {
        this(provider, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TraceService(TraceProvider provider, RuntimeOperationsService runtimeOperationsService) {
        this.provider = provider;
        this.runtimeOperationsService = runtimeOperationsService;
    }

    public List<TraceObservation> query(TraceQuery query) {
        TraceQuery resolved = query == null ? new TraceQuery(null, null, null, null, null, null) : query;
        Map<String, TraceObservation> values = new LinkedHashMap<>();
        provider.query(resolved).forEach(item -> values.put(traceKey(item), item));
        if (runtimeOperationsService != null) {
            runtimeOperationsService.summaries().stream()
                    .filter(summary -> summary.traceRef() != null && !summary.traceRef().isBlank())
                    .filter(summary -> summary.occurredAt().toInstant().isAfter(resolved.effectiveNow().minusSeconds(resolved.window().seconds())))
                    .filter(summary -> resolved.skillId() == null || resolved.skillId().equals(summary.skillId()))
                    .filter(summary -> resolved.version() == null || resolved.version().equals(summary.version()))
                    .filter(summary -> resolved.traceId() == null || resolved.traceId().equals(summary.traceRef()))
                    .filter(summary -> resolved.status() == null || resolved.status().equals(summary.status()))
                    .filter(summary -> resolved.dataSource() == null || "all".equals(resolved.dataSource())
                            || resolved.dataSource().equals(summary.dataSource()))
                    .filter(summary -> resolved.runtimeId() == null || resolved.runtimeId().equals(summary.runtimeId()))
                    .filter(summary -> resolved.mcpServerId() == null || resolved.mcpServerId().equals(summary.mcpServerId()))
                    .filter(summary -> resolved.llmProviderId() == null || resolved.llmProviderId().equals(summary.llmProviderId()))
                    .forEach(summary -> {
                        TraceObservation observation = new TraceObservation(summary.traceRef(),
                                summary.eventId().toString(), summary.skillId(), summary.version(), "skill.run",
                                summary.status(), summary.durationMs(), summary.errorCode(), summary.dataSource(),
                                summary.occurredAt(), summary.runtimeId(), summary.mcpServerId(), summary.llmProviderId());
                        values.put(traceKey(observation), observation);
                    });
        }
        return values.values().stream().sorted(java.util.Comparator.comparing(TraceObservation::occurredAt).reversed()
                .thenComparing(TraceObservation::spanId)).toList();
    }

    private String traceKey(TraceObservation observation) {
        return observation.traceId() + "\u0000" + observation.spanId();
    }

    public String providerId() {
        return provider.providerId();
    }
}
