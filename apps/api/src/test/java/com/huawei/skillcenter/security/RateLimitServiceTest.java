package com.huawei.skillcenter.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {
    @Test
    void rejectsRequestsAfterLimitAndRecoversWhenWindowExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T00:00:00Z"));
        RateLimitService service = new RateLimitService(clock,
                Map.of("TEST", new RateLimitRule(2, Duration.ofSeconds(10))));

        assertThat(service.check("TEST", "user-1").allowed()).isTrue();
        assertThat(service.check("TEST", "user-1").allowed()).isTrue();
        RateLimitDecision rejected = service.check("TEST", "user-1");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(10);

        clock.advanceSeconds(10);
        assertThat(service.check("TEST", "user-1").allowed()).isTrue();
    }

    @Test
    void isolatesSubjectsAndDoesNotExceedLimitUnderConcurrency() throws Exception {
        RateLimitService service = new RateLimitService(Clock.systemUTC(),
                Map.of("TEST", new RateLimitRule(5, Duration.ofMinutes(1))));
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            var futures = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(index -> executor.submit(() -> Boolean.valueOf(service.check("TEST", "same-user").allowed())))
                    .toList();
            long allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) allowed++;
            }
            assertThat(allowed).isEqualTo(5);
            assertThat(service.check("TEST", "other-user").allowed()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(java.time.ZoneId zone) { return this; }

        @Override
        public Instant instant() { return instant; }
    }
}
