package com.huawei.skillcenter.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceTest {
    @Test
    void sameKeyAndFingerprintIsReplayAndDifferentFingerprintIsConflict() {
        IdempotencyService service = new IdempotencyService(
                Clock.fixed(Instant.parse("2026-08-18T04:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(15));

        service.claim("INSTALLATION_CREATE", "alice", "request-1", "fingerprint-a");

        assertThatThrownBy(() -> service.claim("INSTALLATION_CREATE", "alice", "request-1", "fingerprint-a"))
                .isInstanceOf(IdempotencyException.class)
                .extracting(exception -> ((IdempotencyException) exception).code())
                .isEqualTo("IDEMPOTENCY_REPLAY");
        assertThatThrownBy(() -> service.claim("INSTALLATION_CREATE", "alice", "request-1", "fingerprint-b"))
                .isInstanceOf(IdempotencyException.class)
                .extracting(exception -> ((IdempotencyException) exception).code())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void expiredEntryCanBeClaimedAgainAndReleaseRemovesEntry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T04:00:00Z"));
        IdempotencyService service = new IdempotencyService(clock, Duration.ofSeconds(10));
        service.claim("EXPORT_CREATE", "alice", "request-2", "fingerprint-a");
        clock.advanceSeconds(11);
        service.claim("EXPORT_CREATE", "alice", "request-2", "fingerprint-b");
        service.release("EXPORT_CREATE", "alice", "request-2", "fingerprint-b");
        service.claim("EXPORT_CREATE", "alice", "request-2", "fingerprint-c");

        assertThat(service.size()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedKeysButMissingOptionalKeyIsIgnored() {
        IdempotencyService service = new IdempotencyService(Clock.systemUTC(), Duration.ofMinutes(15));
        service.claim("EXPORT_CREATE", "alice", null, "fingerprint-a");

        assertThatThrownBy(() -> service.claim("EXPORT_CREATE", "alice", " ", "fingerprint-a"))
                .isInstanceOf(IdempotencyException.class)
                .extracting(exception -> ((IdempotencyException) exception).code())
                .isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThatThrownBy(() -> service.claim("EXPORT_CREATE", "alice", "a\nkey", "fingerprint-a"))
                .isInstanceOf(IdempotencyException.class)
                .extracting(exception -> ((IdempotencyException) exception).code())
                .isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThatThrownBy(() -> service.claim("EXPORT_CREATE", "alice", "x".repeat(129), "fingerprint-a"))
                .isInstanceOf(IdempotencyException.class)
                .extracting(exception -> ((IdempotencyException) exception).code())
                .isEqualTo("IDEMPOTENCY_KEY_INVALID");
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
