package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsReleaseGateTest {
    @Test
    void persistedMetricsRecoverAndKeepPrometheusOutputAggregateOnly() throws Exception {
        Path file = Files.createTempDirectory("operations-rc-gate").resolve("metrics.json");
        Clock clock = Clock.fixed(Instant.parse("2026-08-18T06:00:10Z"), ZoneOffset.UTC);
        OperationsMetricsService first = new OperationsMetricsService(
                clock, "", false, new JsonOperationsMetricsStore(file, new ObjectMapper()));
        first.recordRequest(200, 120);

        OperationsMetricsService restored = new OperationsMetricsService(
                clock, "", false, new JsonOperationsMetricsStore(file, new ObjectMapper()));
        OperationsMetricsSnapshot snapshot = restored.snapshot(OperationsWindow.SIXTY_MINUTES);
        String output = new PrometheusMetricsController(restored, "gate-token")
                .metrics("gate-token").getBody();

        assertThat(snapshot.requests().total()).isEqualTo(1);
        assertThat(snapshot.health().metricsPersistence()).isEqualTo("ENABLED");
        assertThat(output).contains("skillcenter_requests_total")
                .doesNotContain("gate-token", "userId", "requestBody", "http_");
    }

    @Test
    void sharedAggregationAndAlertNotificationRemainBounded() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-18T06:00:10Z"), ZoneOffset.UTC);
        SharedClient client = new SharedClient();
        OperationsMetricsService first = new OperationsMetricsService(
                clock, "", false, new RedisOperationsMetricsStore(client));
        OperationsMetricsService second = new OperationsMetricsService(
                clock, "", false, new RedisOperationsMetricsStore(client));
        RecordingSink sink = new RecordingSink();
        OperationsAlertService alerts = new OperationsAlertService(clock, first, 100, 0.5, 2, 2, sink);

        first.recordRequest(200, 20);
        second.recordRequest(500, 1_500);
        alerts.evaluate(OperationsWindow.FIVE_MINUTES);
        alerts.evaluate(OperationsWindow.FIVE_MINUTES);

        assertThat(first.snapshot(OperationsWindow.FIVE_MINUTES).requests().total()).isEqualTo(2);
        assertThat(sink.notifications).hasSize(2);
        assertThat(sink.notifications).allSatisfy(alert ->
                assertThat(alert.status()).isEqualTo("ACTIVE"));
    }

    private static final class RecordingSink implements OperationsAlertNotificationSink {
        private final List<OperationsAlertSnapshot> notifications = new java.util.ArrayList<>();

        @Override
        public void notify(OperationsAlertSnapshot alert) {
            notifications.add(alert);
        }
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
}
