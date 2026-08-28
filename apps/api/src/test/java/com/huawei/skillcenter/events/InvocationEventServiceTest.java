package com.huawei.skillcenter.events;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvocationEventServiceTest {
    private final InvocationEventService service = new InvocationEventService();

    @Test
    void duplicateEventIdDoesNotIncrementInvocationCountTwice() {
        InvocationEvent event = validEvent(UUID.randomUUID(), "eox-query");

        assertThat(service.ingest(event).duplicate()).isFalse();
        assertThat(service.ingest(event).duplicate()).isTrue();
        assertThat(service.totalCalls("eox-query")).isEqualTo(1);
    }

    @Test
    void failureRequiresErrorCode() {
        InvocationEvent event = new InvocationEvent(
                "1.0", UUID.randomUUID(), OffsetDateTime.now(), "eox-query", "1.2.0",
                new InvocationEvent.Subject("user-1", "network-team"),
                new InvocationEvent.Client("codex", "1.0.0"),
                "session_1234567890", "failure", 42, null, null);

        assertThatThrownBy(() -> service.ingest(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorCode");
    }

    @Test
    void batchReportsAcceptedDuplicateAndConflictingEventSeparately() {
        UUID duplicateId = UUID.randomUUID();
        InvocationEvent duplicate = validEvent(duplicateId, "eox-query");
        service.ingest(duplicate);
        InvocationEvent conflict = new InvocationEvent(
                "1.0", duplicateId, OffsetDateTime.now(), "eox-query", "1.2.0",
                new InvocationEvent.Subject("user-1", "network-team"),
                new InvocationEvent.Client("codex", "1.0.0"),
                "session_1234567890", "failure", 42, "UPSTREAM_TIMEOUT", null);

        InvocationEventBatchResponse response = service.ingestBatch(new InvocationEventBatchRequest(
                "batch-1", "1.0", List.of(validEvent(UUID.randomUUID(), "eox-query"),
                duplicate, conflict)));

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.duplicates()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.results().get(2).errorCode()).isEqualTo("EVENT_ID_CONFLICT");
    }

    @Test
    void ingestionStatsRetainsQualityOutcomeWithoutPayload() {
        UUID id = UUID.randomUUID();
        InvocationEvent event = validEvent(id, "eox-query");
        service.ingest(event);
        service.ingest(event);
        service.ingestBatch(new InvocationEventBatchRequest("batch-quality", "1.0", List.of(invalidEvent())));

        assertThat(service.ingestionStats().entries())
                .extracting(InvocationIngestionStats.Entry::result)
                .containsExactly(InvocationIngestionStats.Result.ACCEPTED,
                        InvocationIngestionStats.Result.DUPLICATE,
                        InvocationIngestionStats.Result.REJECTED);
        assertThat(service.ingestionStats().entries().get(0).eventId()).isEqualTo(id);
        assertThat(service.ingestionStats().entries().get(0).toString())
                .doesNotContain("prompt", "output", "token");
    }

    private InvocationEvent invalidEvent() {
        return new InvocationEvent("1.0", UUID.randomUUID(), OffsetDateTime.now(), "", "1.2.0",
                new InvocationEvent.Subject("user-1", "network-team"),
                new InvocationEvent.Client("codex", "1.0.0"),
                "session_1234567890", "success", 42, null, null);
    }

    private InvocationEvent validEvent(UUID eventId, String skillId) {
        return new InvocationEvent(
                "1.0", eventId, OffsetDateTime.now(), skillId, "1.2.0",
                new InvocationEvent.Subject("user-1", "network-team"),
                new InvocationEvent.Client("codex", "1.0.0"),
                "session_1234567890", "success", 42, null,
                new InvocationEvent.Usage("gpt-5", 10, 20));
    }
}
