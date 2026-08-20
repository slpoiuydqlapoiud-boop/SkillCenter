package com.huawei.skillcenter.events;

import com.huawei.skillcenter.governance.GovernanceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GovernanceInvocationEventStore implements InvocationEventStore {
    private final GovernanceStore governanceStore;
    private final boolean persistent;
    private final Map<UUID, InvocationEvent> inMemoryEvents = new ConcurrentHashMap<>();

    public GovernanceInvocationEventStore(GovernanceStore governanceStore) {
        this(governanceStore, true);
    }

    @Autowired
    public GovernanceInvocationEventStore(GovernanceStore governanceStore,
                                          @Value("${skill-center.invocation-persistence:true}") boolean persistent) {
        this.governanceStore = governanceStore;
        this.persistent = persistent;
    }

    @Override
    public synchronized Collection<InvocationEvent> events() {
        return persistent ? governanceStore.snapshot().invocationEvents() : inMemoryEvents.values();
    }

    @Override
    public synchronized InvocationEventService.IngestResult putIfAbsent(InvocationEvent event) {
        if (!persistent) {
            InvocationEvent existing = inMemoryEvents.putIfAbsent(event.eventId(), event);
            if (existing != null && !existing.equals(event)) {
                throw new InvocationEventConflictException("eventId has already been used with different content");
            }
            return new InvocationEventService.IngestResult(event.eventId(), existing != null);
        }
        List<InvocationEvent> events = new ArrayList<>(governanceStore.snapshot().invocationEvents());
        InvocationEvent existing = events.stream()
                .filter(candidate -> candidate.eventId().equals(event.eventId()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (!existing.equals(event)) {
                throw new InvocationEventConflictException("eventId has already been used with different content");
            }
            return new InvocationEventService.IngestResult(event.eventId(), true);
        }
        events.add(event);
        governanceStore.updateInvocationEvents(events);
        return new InvocationEventService.IngestResult(event.eventId(), false);
    }

    @Override
    public synchronized int deleteBefore(Instant cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff is required");
        }
        if (!persistent) {
            int removed = 0;
            for (InvocationEvent event : inMemoryEvents.values()) {
                if (event.occurredAt().toInstant().isBefore(cutoff) && inMemoryEvents.remove(event.eventId(), event)) {
                    removed++;
                }
            }
            return removed;
        }
        List<InvocationEvent> retained = governanceStore.snapshot().invocationEvents().stream()
                .filter(event -> !event.occurredAt().toInstant().isBefore(cutoff))
                .toList();
        int removed = governanceStore.snapshot().invocationEvents().size() - retained.size();
        if (removed > 0) {
            governanceStore.updateInvocationEvents(retained);
        }
        return removed;
    }

    @Override
    public synchronized long countBefore(Instant cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff is required");
        }
        return events().stream()
                .filter(event -> event.occurredAt().toInstant().isBefore(cutoff))
                .count();
    }
}
