package com.huawei.skillcenter.operations;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RuntimeSummaryService {
    private static final int MAX_BATCH_SIZE = 100;
    private static final java.util.Set<String> STATUSES = java.util.Set.of(
            "success", "failure", "timeout", "cancelled");
    private static final java.util.Set<String> DATA_SOURCES = java.util.Set.of("mock", "production");
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private final RuntimeSummaryRepository store;
    private final Clock clock;

    public RuntimeSummaryService() {
        this(new RuntimeSummaryStore(), Clock.systemUTC());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeSummaryService(RuntimeSummaryRepository store) {
        this(store, Clock.systemUTC());
    }

    public RuntimeSummaryService(RuntimeSummaryRepository store, Clock clock) {
        this.store = store;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public IngestResult ingest(RuntimeSummary summary) {
        validate(summary);
        return new IngestResult(summary.eventId(), store.putIfAbsent(summary));
    }

    public RuntimeSummaryBatchResponse ingestBatch(RuntimeSummaryBatchRequest batch) {
        if (batch == null || blank(batch.batchId()) || !"1.0".equals(batch.schemaVersion())
                || batch.events() == null || batch.events().isEmpty() || batch.events().size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("runtime summary batch is invalid");
        }
        List<RuntimeSummaryBatchResponse.BatchResult> results = new ArrayList<>();
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        for (RuntimeSummary event : batch.events()) {
            try {
                IngestResult result = ingest(event);
                results.add(new RuntimeSummaryBatchResponse.BatchResult(result.eventId(), true,
                        result.duplicate(), null));
                if (result.duplicate()) {
                    duplicates++;
                } else {
                    accepted++;
                }
            } catch (RuntimeSummaryConflictException exception) {
                results.add(new RuntimeSummaryBatchResponse.BatchResult(event == null ? null : event.eventId(),
                        false, false, "EVENT_ID_CONFLICT"));
                rejected++;
            } catch (IllegalArgumentException exception) {
                results.add(new RuntimeSummaryBatchResponse.BatchResult(event == null ? null : event.eventId(),
                        false, false, "EVENT_SCHEMA_INVALID"));
                rejected++;
            }
        }
        return new RuntimeSummaryBatchResponse(batch.batchId(), accepted, duplicates, rejected, results);
    }

    public List<RuntimeSummary> events() {
        return store.findAll();
    }

    public void clear() {
        store.clear();
    }

    private void validate(RuntimeSummary summary) {
        if (summary == null || summary.eventId() == null || summary.occurredAt() == null
                || blank(summary.skillId()) || blank(summary.version()) || blank(summary.status())
                || blank(summary.dataSource())) {
            throw new IllegalArgumentException("eventId, occurredAt, skillId, version, status and dataSource are required");
        }
        if (summary.occurredAt().toInstant().isAfter(clock.instant().plus(MAX_FUTURE_SKEW))) {
            throw new IllegalArgumentException("occurredAt is beyond the allowed clock skew");
        }
        if (!"1.0".equals(summary.schemaVersion())) {
            throw new IllegalArgumentException("schemaVersion must be 1.0");
        }
        if (!STATUSES.contains(summary.status())) {
            throw new IllegalArgumentException("status is invalid");
        }
        if (!DATA_SOURCES.contains(summary.dataSource())) {
            throw new IllegalArgumentException("dataSource must be mock or production");
        }
        if (summary.durationMs() < 0 || summary.durationMs() > 86_400_000L) {
            throw new IllegalArgumentException("durationMs is out of range");
        }
        boolean needsError = "failure".equals(summary.status()) || "timeout".equals(summary.status());
        if (needsError && blank(summary.errorCode())) {
            throw new IllegalArgumentException("errorCode is required for failure and timeout summaries");
        }
        if (!needsError && summary.errorCode() != null && !summary.errorCode().isBlank()) {
            throw new IllegalArgumentException("errorCode is only allowed for failure and timeout summaries");
        }
        validateLength(summary.teamId(), 128, "teamId");
        validateLength(summary.clientType(), 128, "clientType");
        validateLength(summary.traceRef(), 256, "traceRef");
        validateEnvironment(summary.runtimeId(), "runtimeId");
        validateEnvironment(summary.mcpServerId(), "mcpServerId");
        validateEnvironment(summary.llmProviderId(), "llmProviderId");
    }

    private void validateLength(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " is too long");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void validateEnvironment(String value, String field) {
        if (value == null || value.isBlank()) return;
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }

    public record IngestResult(UUID eventId, boolean duplicate) {
    }
}
