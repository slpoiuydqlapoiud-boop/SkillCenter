package com.huawei.skillcenter.events;

import com.huawei.skillcenter.governance.GovernanceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationEventStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptedEventsAreIdempotentPersistentAndRetainable() {
        Path state = tempDir.resolve("invocations.json");
        GovernanceStore store = new GovernanceStore(state, java.util.List.of());
        InvocationEventStore eventStore = new GovernanceInvocationEventStore(store);
        InvocationEvent event = event("2026-08-17T10:00:00+08:00", "eox-query");

        assertThat(eventStore.putIfAbsent(event).duplicate()).isFalse();
        assertThat(eventStore.putIfAbsent(event).duplicate()).isTrue();
        assertThat(eventStore.events()).extracting(InvocationEvent::eventId).containsExactly(event.eventId());

        GovernanceStore restarted = new GovernanceStore(state, java.util.List.of());
        assertThat(new GovernanceInvocationEventStore(restarted).events())
                .extracting(InvocationEvent::eventId).containsExactly(event.eventId());
        assertThat(new GovernanceInvocationEventStore(restarted)
                .deleteBefore(Instant.parse("2026-08-18T00:00:00Z"))).isEqualTo(1);
        assertThat(new GovernanceInvocationEventStore(restarted).events()).isEmpty();
    }

    private InvocationEvent event(String occurredAt, String skillId) {
        return new InvocationEvent("1.0", UUID.randomUUID(), OffsetDateTime.parse(occurredAt), skillId,
                "1.2.0", new InvocationEvent.Subject("user-1", "team-a"),
                new InvocationEvent.Client("codex", "1.0.0"), "session_1234567890",
                "success", 42, null, new InvocationEvent.Usage("gpt-5", 10, 20));
    }
}
