package com.huawei.skillcenter.events;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class InvocationEventService {
    private static final int MAX_INGESTION_RECEIPTS = 10_000;
    private final InvocationEventStore eventStore;
    private final Deque<InvocationIngestionStats.Entry> ingestionReceipts = new ArrayDeque<>();

    public InvocationEventService() {
        this(new InMemoryInvocationEventStore());
    }

    @Autowired
    public InvocationEventService(InvocationEventStore eventStore) {
        this.eventStore = eventStore;
    }

    public IngestResult ingest(InvocationEvent event) {
        try {
            validate(event);
        } catch (IllegalArgumentException exception) {
            recordReceipt(event, InvocationIngestionStats.Result.REJECTED);
            throw exception;
        }
        IngestResult result;
        try {
            result = eventStore.putIfAbsent(event);
        } catch (InvocationEventConflictException exception) {
            recordReceipt(event, InvocationIngestionStats.Result.REJECTED);
            throw exception;
        }
        recordReceipt(event, !result.duplicate()
                ? InvocationIngestionStats.Result.ACCEPTED
                : InvocationIngestionStats.Result.DUPLICATE);
        return result;
    }

    public InvocationEventBatchResponse ingestBatch(InvocationEventBatchRequest batch) {
        if (batch == null || batch.batchId() == null || batch.batchId().isBlank()
                || !"1.0".equals(batch.schemaVersion()) || batch.events() == null
                || batch.events().isEmpty() || batch.events().size() > 100) {
            throw new IllegalArgumentException("invocation event batch is invalid");
        }
        List<InvocationEventBatchResponse.BatchResult> results = new ArrayList<>();
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        for (InvocationEvent event : batch.events()) {
            try {
                IngestResult result = ingest(event);
                results.add(new InvocationEventBatchResponse.BatchResult(result.eventId(), true, result.duplicate(), null));
                if (result.duplicate()) {
                    duplicates++;
                } else {
                    accepted++;
                }
            } catch (InvocationEventConflictException exception) {
                results.add(new InvocationEventBatchResponse.BatchResult(event == null ? null : event.eventId(),
                        false, false, "EVENT_ID_CONFLICT"));
                rejected++;
            } catch (IllegalArgumentException exception) {
                results.add(new InvocationEventBatchResponse.BatchResult(event == null ? null : event.eventId(),
                        false, false, "EVENT_SCHEMA_INVALID"));
                rejected++;
            }
        }
        return new InvocationEventBatchResponse(batch.batchId(), accepted, duplicates, rejected, List.copyOf(results));
    }

    public long totalCalls(String skillId) {
        return eventStore.events().stream().filter(event -> event.skillId().equals(skillId)).count();
    }

    public long successfulCalls(String skillId) {
        return eventStore.events().stream()
                .filter(event -> event.skillId().equals(skillId) && "success".equals(event.status()))
                .count();
    }

    public Collection<InvocationEvent> events() {
        return eventStore.events();
    }

    public int deleteBefore(Instant cutoff) {
        return eventStore.deleteBefore(cutoff);
    }

    public InvocationIngestionStats ingestionStats() {
        synchronized (ingestionReceipts) {
            return new InvocationIngestionStats(List.copyOf(ingestionReceipts));
        }
    }

    private void recordReceipt(InvocationEvent event, InvocationIngestionStats.Result result) {
        InvocationIngestionStats.Entry receipt = new InvocationIngestionStats.Entry(
                event == null ? null : event.eventId(),
                event == null ? null : event.occurredAt(),
                Instant.now(),
                result);
        synchronized (ingestionReceipts) {
            ingestionReceipts.addLast(receipt);
            while (ingestionReceipts.size() > MAX_INGESTION_RECEIPTS) {
                ingestionReceipts.removeFirst();
            }
        }
    }

    private void validate(InvocationEvent event) {
        if (event == null || event.eventId() == null || event.occurredAt() == null
                || event.skillId() == null || event.skillId().isBlank()) {
            throw new IllegalArgumentException("eventId, occurredAt and skillId are required");
        }
        if (!"1.0".equals(event.schemaVersion())) {
            throw new IllegalArgumentException("schemaVersion must be 1.0");
        }
        if (event.subject() == null || event.client() == null || event.sessionId() == null
                || event.sessionId().length() < 16 || event.status() == null) {
            throw new IllegalArgumentException("subject, client, sessionId and status are required");
        }
        if (!java.util.Set.of("success", "failure", "cancelled", "timeout").contains(event.status())) {
            throw new IllegalArgumentException("status is invalid");
        }
        if (event.durationMs() < 0 || event.durationMs() > 86_400_000L) {
            throw new IllegalArgumentException("durationMs is out of range");
        }
        boolean requiresError = "failure".equals(event.status()) || "timeout".equals(event.status());
        if (requiresError && (event.errorCode() == null || event.errorCode().isBlank())) {
            throw new IllegalArgumentException("errorCode is required for failure and timeout events");
        }
        if (!requiresError && event.errorCode() != null) {
            throw new IllegalArgumentException("errorCode is only allowed for failure and timeout events");
        }
    }

    public record IngestResult(UUID eventId, boolean duplicate) {}
}
