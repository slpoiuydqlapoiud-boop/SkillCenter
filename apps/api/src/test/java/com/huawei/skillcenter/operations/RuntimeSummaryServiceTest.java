package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeSummaryServiceTest {
    @Test
    void ingestsIdempotentlyAndRejectsConflictingEventIds() {
        RuntimeSummaryService service = new RuntimeSummaryService();
        UUID eventId = UUID.randomUUID();
        RuntimeSummary first = summary(eventId, "success", null, "production");

        assertThat(service.ingest(first).duplicate()).isFalse();
        assertThat(service.ingest(first).duplicate()).isTrue();
        assertThat(service.events()).containsExactly(first);

        RuntimeSummary conflict = summary(eventId, "failure", "TOOL_ERROR", "production");
        assertThatThrownBy(() -> service.ingest(conflict))
                .isInstanceOf(RuntimeSummaryConflictException.class);
    }

    @Test
    void validatesDataSourceStatusDurationAndErrorCode() {
        RuntimeSummaryService service = new RuntimeSummaryService();
        assertThatThrownBy(() -> service.ingest(summary(UUID.randomUUID(), "success", "BAD", "production")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(summary(UUID.randomUUID(), "failure", null, "production")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(summary(UUID.randomUUID(), "success", null, "sandbox")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEventsBeyondTheAllowedFutureSkew() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T02:00:00Z"), ZoneOffset.UTC);
        RuntimeSummaryService service = new RuntimeSummaryService(new RuntimeSummaryStore(), clock);
        RuntimeSummary future = new RuntimeSummary("1.0", UUID.randomUUID(),
                OffsetDateTime.parse("2026-08-24T02:06:00Z"), "skill-a", "1.0.0", "success",
                120, null, "production", "team-a", "codex", null);

        assertThatThrownBy(() -> service.ingest(future))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("occurredAt is beyond the allowed clock skew");
    }

    private RuntimeSummary summary(UUID eventId, String status, String errorCode, String dataSource) {
        return new RuntimeSummary("1.0", eventId,
                OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.ofHours(8)),
                "skill-a", "1.0.0", status, 120, errorCode, dataSource, "team-a", "codex", null);
    }
}
