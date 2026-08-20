package com.huawei.skillcenter.events;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryInvocationEventStore implements InvocationEventStore {
    private final Map<UUID, InvocationEvent> events = new ConcurrentHashMap<>();

    @Override
    public Collection<InvocationEvent> events() {
        return events.values();
    }

    @Override
    public InvocationEventService.IngestResult putIfAbsent(InvocationEvent event) {
        InvocationEvent existing = events.putIfAbsent(event.eventId(), event);
        if (existing != null && !existing.equals(event)) {
            throw new InvocationEventConflictException("eventId has already been used with different content");
        }
        return new InvocationEventService.IngestResult(event.eventId(), existing != null);
    }

    @Override
    public int deleteBefore(Instant cutoff) {
        int removed = 0;
        for (InvocationEvent event : events.values()) {
            if (event.occurredAt().toInstant().isBefore(cutoff) && events.remove(event.eventId(), event)) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public long countBefore(Instant cutoff) {
        return events.values().stream()
                .filter(event -> event.occurredAt().toInstant().isBefore(cutoff))
                .count();
    }
}
