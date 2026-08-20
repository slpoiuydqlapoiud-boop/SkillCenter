package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RedisOperationsMetricsStoreTest {
    @Test
    void concurrentMergesPreserveCountersHistogramAndSecurityEvents() throws Exception {
        FakeClient client = new FakeClient();
        RedisOperationsMetricsStore store = new RedisOperationsMetricsStore(client);
        int workers = 8;
        int eventsPerWorker = 2_500;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> tasks = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            tasks.add(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    for (int event = 0; event < eventsPerWorker; event++) {
                        store.merge(List.of(new OperationsMetricsStore.Bucket(
                                29_760_000L, 1, 1, 0, 0, 120,
                                new long[]{0, 1, 0, 0, 0, 0, 0, 0},
                                Map.of("RATE_LIMITED", 1L))));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submit(tasks.get(tasks.size() - 1));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        OperationsMetricsStore.Bucket bucket = store.load().get(0);
        int expected = workers * eventsPerWorker;
        assertThat(bucket.total()).isEqualTo(expected);
        assertThat(bucket.successes()).isEqualTo(expected);
        assertThat(bucket.latencyCounts()).containsExactly(0, expected, 0, 0, 0, 0, 0, 0);
        assertThat(bucket.securityEvents()).containsEntry("RATE_LIMITED", (long) expected);
        assertThat(store.sharedReads()).isTrue();
    }

    private static final class FakeClient implements RedisOperationsMetricsStore.Client {
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
