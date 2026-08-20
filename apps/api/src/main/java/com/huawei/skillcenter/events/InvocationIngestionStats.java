package com.huawei.skillcenter.events;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InvocationIngestionStats(List<Entry> entries) {
    public InvocationIngestionStats {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    public enum Result {
        ACCEPTED,
        DUPLICATE,
        REJECTED
    }

    public record Entry(UUID eventId, OffsetDateTime occurredAt, Instant receivedAt, Result result) {
        public Entry {
            if (receivedAt == null || result == null) {
                throw new IllegalArgumentException("receivedAt and result are required");
            }
        }
    }
}
