package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsMetricsServiceTest {
    @Test
    void parsesOnlySupportedWindows() {
        assertThat(OperationsWindow.parse("5m")).isEqualTo(OperationsWindow.FIVE_MINUTES);
        assertThat(OperationsWindow.parse("15m")).isEqualTo(OperationsWindow.FIFTEEN_MINUTES);
        assertThat(OperationsWindow.parse("60m")).isEqualTo(OperationsWindow.SIXTY_MINUTES);
        assertThatThrownBy(() -> OperationsWindow.parse("1h"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregatesStatusLatencyAndSecurityEventsWithinWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService service = new OperationsMetricsService(clock, "./data/packages", true);
        service.recordRequest(200, 5);
        service.recordRequest(201, 15);
        service.recordRequest(404, 80);
        service.recordRequest(503, 2_500);
        service.recordSecurityEvent("RATE_LIMITED");
        service.recordSecurityEvent("RATE_LIMITED");
        service.recordSecurityEvent("CSRF_ORIGIN_REJECTED");

        OperationsMetricsSnapshot snapshot = service.snapshot(OperationsWindow.FIFTEEN_MINUTES);

        assertThat(snapshot.requests().total()).isEqualTo(4);
        assertThat(snapshot.requests().successes()).isEqualTo(2);
        assertThat(snapshot.requests().clientErrors()).isEqualTo(1);
        assertThat(snapshot.requests().serverErrors()).isEqualTo(1);
        assertThat(snapshot.latency().p50Ms()).isEqualTo(49);
        assertThat(snapshot.latency().p95Ms()).isEqualTo(2_000);
        assertThat(snapshot.latency().maxMs()).isEqualTo(2_500);
        assertThat(snapshot.securityEvents()).containsEntry("RATE_LIMITED", 2L)
                .containsEntry("CSRF_ORIGIN_REJECTED", 1L);
        assertThat(snapshot.health().status()).isEqualTo("UP");
        assertThat(snapshot.health().packageStorage()).isEqualTo("CONFIGURED");
        assertThat(snapshot.health().invocationPersistence()).isEqualTo("ENABLED");
        assertThat(snapshot.health().metricsPersistence()).isEqualTo("DISABLED");
    }

    @Test
    void oldBucketsExpireAndNegativeLatencyIsClamped() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService service = new OperationsMetricsService(clock, "", false);
        service.recordRequest(200, -5);
        clock.advanceSeconds(6 * 60);

        OperationsMetricsSnapshot snapshot = service.snapshot(OperationsWindow.FIVE_MINUTES);

        assertThat(snapshot.requests().total()).isZero();
        assertThat(snapshot.latency().maxMs()).isZero();
        assertThat(snapshot.health().packageStorage()).isEqualTo("NOT_CONFIGURED");
        assertThat(snapshot.health().invocationPersistence()).isEqualTo("DISABLED");
    }

    @Test
    void clearRemovesAllBuckets() {
        OperationsMetricsService service = new OperationsMetricsService(
                Clock.fixed(Instant.parse("2026-08-18T06:00:10Z"), ZoneOffset.UTC), "./data/packages", true);
        service.recordRequest(200, 10);
        service.recordSecurityEvent("IDEMPOTENCY_CONFLICT");
        service.clear();

        OperationsMetricsSnapshot snapshot = service.snapshot(OperationsWindow.SIXTY_MINUTES);

        assertThat(snapshot.requests().total()).isZero();
        assertThat(snapshot.securityEvents()).isEmpty();
    }

    @Test
    void sharedStoreSnapshotIncludesEventsRecordedByAnotherInstance() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        SharedClient client = new SharedClient();
        OperationsMetricsService first = new OperationsMetricsService(
                clock, "", false, new RedisOperationsMetricsStore(client));
        OperationsMetricsService second = new OperationsMetricsService(
                clock, "", false, new RedisOperationsMetricsStore(client));

        first.recordRequest(200, 10);
        second.recordRequest(500, 120);
        second.recordSecurityEvent("RATE_LIMITED");

        OperationsMetricsSnapshot snapshot = first.snapshot(OperationsWindow.FIVE_MINUTES);

        assertThat(snapshot.requests().total()).isEqualTo(2);
        assertThat(snapshot.requests().serverErrors()).isEqualTo(1);
        assertThat(snapshot.securityEvents()).containsEntry("RATE_LIMITED", 1L);
        assertThat(snapshot.health().metricsPersistence()).isEqualTo("ENABLED");
    }

    @Test
    void localMetricsReadinessIsNotReadyForSharedScheduling() {
        OperationsMetricsService service = new OperationsMetricsService(
                Clock.fixed(Instant.parse("2026-08-18T06:00:10Z"), ZoneOffset.UTC), "", false);

        OperationsMetricsReadiness readiness = service.readiness();

        assertThat(readiness.backend()).isEqualTo("local");
        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.shared()).isFalse();
        assertThat(readiness.reasonCode()).isEqualTo("OPERATIONS_METRICS_SHARED_STORE_REQUIRED");
    }

    @Test
    void enabledRedisMetricsReadinessIsReadyForSharedScheduling() {
        OperationsMetricsService service = new OperationsMetricsService(
                Clock.fixed(Instant.parse("2026-08-18T06:00:10Z"), ZoneOffset.UTC), "", false,
                new RedisOperationsMetricsStore(new SharedClient()));

        OperationsMetricsReadiness readiness = service.readiness();

        assertThat(readiness.backend()).isEqualTo("redis");
        assertThat(readiness.status()).isEqualTo("READY");
        assertThat(readiness.shared()).isTrue();
        assertThat(readiness.reasonCode()).isEqualTo("OPERATIONS_METRICS_REDIS_READY");
    }

    private static final class SharedClient implements RedisOperationsMetricsStore.Client {
        private final Map<Long, OperationsMetricsStore.Bucket> buckets = new ConcurrentHashMap<>();

        @Override
        public synchronized List<OperationsMetricsStore.Bucket> load() {
            return List.copyOf(buckets.values());
        }

        @Override
        public synchronized void merge(OperationsMetricsStore.Bucket delta) {
            buckets.merge(delta.bucketKey(), delta, OperationsMetricsStore::combine);
        }

        @Override
        public synchronized void clear() {
            buckets.clear();
        }

        @Override
        public String status() {
            return "ENABLED";
        }
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
