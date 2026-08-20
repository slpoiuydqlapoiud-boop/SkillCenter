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

import static org.assertj.core.api.Assertions.assertThat;

class JsonOperationsMetricsStoreTest {
    @Test
    void persistsAndRestoresAggregatedBuckets() throws Exception {
        Path directory = Files.createTempDirectory("operations-metrics-store");
        Path file = directory.resolve("metrics.json");
        JsonOperationsMetricsStore first = new JsonOperationsMetricsStore(file, new ObjectMapper());
        OperationsMetricsStore.Bucket bucket = new OperationsMetricsStore.Bucket(
                29_760_000L, 4, 2, 1, 1, 2_500,
                new long[]{2, 0, 1, 0, 0, 0, 0, 1},
                Map.of("RATE_LIMITED", 2L));

        first.save(List.of(bucket));

        JsonOperationsMetricsStore second = new JsonOperationsMetricsStore(file, new ObjectMapper());
        assertThat(second.load()).hasSize(1);
        OperationsMetricsStore.Bucket restored = second.load().get(0);
        assertThat(restored.bucketKey()).isEqualTo(bucket.bucketKey());
        assertThat(restored.total()).isEqualTo(bucket.total());
        assertThat(restored.latencyCounts()).containsExactly(bucket.latencyCounts());
        assertThat(restored.securityEvents()).containsExactlyInAnyOrderEntriesOf(bucket.securityEvents());
        assertThat(second.status()).isEqualTo("ENABLED");
    }

    @Test
    void corruptedFileDoesNotBlockStartupAndSurfacesDegradedHealth() throws Exception {
        Path file = Files.createTempFile("operations-metrics-corrupt", ".json");
        Files.writeString(file, "not-json");

        OperationsMetricsService service = new OperationsMetricsService(
                Clock.fixed(Instant.parse("2026-08-18T06:00:10Z"), ZoneOffset.UTC),
                "", false, new JsonOperationsMetricsStore(file, new ObjectMapper()));

        assertThat(service.snapshot(OperationsWindow.FIVE_MINUTES).health().status()).isEqualTo("DEGRADED");
        assertThat(service.snapshot(OperationsWindow.FIVE_MINUTES).health().metricsPersistence())
                .isEqualTo("DEGRADED");
    }

    @Test
    void mergeAddsCountersAndSecurityEventsInsteadOfReplacingTheBucket() throws Exception {
        Path file = Files.createTempDirectory("operations-metrics-merge").resolve("metrics.json");
        JsonOperationsMetricsStore store = new JsonOperationsMetricsStore(file, new ObjectMapper());
        long bucketKey = 29_760_000L;

        store.merge(List.of(new OperationsMetricsStore.Bucket(
                bucketKey, 2, 1, 1, 0, 900,
                new long[]{1, 1, 0, 0, 0, 0, 0, 0},
                Map.of("RATE_LIMITED", 1L))));
        store.merge(List.of(new OperationsMetricsStore.Bucket(
                bucketKey, 3, 2, 0, 1, 1_500,
                new long[]{0, 1, 1, 0, 0, 0, 0, 0},
                Map.of("RATE_LIMITED", 2L, "EXPORT_QUEUE_FULL", 1L))));

        OperationsMetricsStore.Bucket merged = store.load().get(0);
        assertThat(merged.total()).isEqualTo(5);
        assertThat(merged.successes()).isEqualTo(3);
        assertThat(merged.clientErrors()).isEqualTo(1);
        assertThat(merged.serverErrors()).isEqualTo(1);
        assertThat(merged.maxMs()).isEqualTo(1_500);
        assertThat(merged.latencyCounts()).containsExactly(1, 2, 1, 0, 0, 0, 0, 0);
        assertThat(merged.securityEvents()).containsExactlyInAnyOrderEntriesOf(
                Map.of("RATE_LIMITED", 3L, "EXPORT_QUEUE_FULL", 1L));
    }
}
