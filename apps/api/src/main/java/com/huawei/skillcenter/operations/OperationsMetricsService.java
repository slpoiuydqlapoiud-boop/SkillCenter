package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

@Service
public class OperationsMetricsService {
    private static final int BUCKET_SECONDS = 60;
    private static final int RETAINED_BUCKETS = 60;
    private static final long[] LATENCY_UPPER_BOUNDS = {9, 49, 99, 249, 499, 999, 1_999, 2_000};
    private static final Set<String> SECURITY_EVENTS = Set.of(
            "RATE_LIMITED", "CSRF_ORIGIN_REJECTED", "IDEMPOTENCY_REPLAY",
            "IDEMPOTENCY_CONFLICT", "EXPORT_QUEUE_FULL");

    private final Clock clock;
    private final String packageStorage;
    private final boolean invocationPersistence;
    private final OperationsMetricsStore store;
    private final ConcurrentHashMap<Long, MetricsBucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public OperationsMetricsService(
            @Value("${skill-center.package-storage:}") String packageStorage,
            @Value("${skill-center.invocation-persistence:false}") boolean invocationPersistence,
            @Value("${skill-center.operations.metrics-storage:./data/operations/metrics.json}") String metricsStorage,
            ObjectMapper objectMapper,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${skill-center.operations.redis.key:skill-center:operations:metrics}") String redisKey,
            @Value("${skill-center.operations.redis.ttl-seconds:3660}") long redisTtlSeconds) {
        this(Clock.systemUTC(), packageStorage, invocationPersistence,
                createStore(metricsStorage, objectMapper, redisTemplateProvider.getIfAvailable(), redisKey,
                        redisTtlSeconds));
    }

    public OperationsMetricsService(Clock clock, String packageStorage, boolean invocationPersistence) {
        this(clock, packageStorage, invocationPersistence, new MemoryOperationsMetricsStore());
    }

    OperationsMetricsService(Clock clock, String packageStorage, boolean invocationPersistence,
                             OperationsMetricsStore store) {
        this.clock = clock;
        this.packageStorage = packageStorage;
        this.invocationPersistence = invocationPersistence;
        this.store = store;
        restore();
    }

    public void recordRequest(int status, long durationMs) {
        long now = clock.instant().getEpochSecond();
        long normalizedDuration = Math.max(0, durationMs);
        purge(now);
        buckets.computeIfAbsent(bucketKey(now), ignored -> new MetricsBucket())
                .recordRequest(status, normalizedDuration);
        persistDelta(requestDelta(bucketKey(now), status, normalizedDuration));
    }

    public void recordSecurityEvent(String eventCode) {
        if (!SECURITY_EVENTS.contains(eventCode)) {
            return;
        }
        long now = clock.instant().getEpochSecond();
        purge(now);
        buckets.computeIfAbsent(bucketKey(now), ignored -> new MetricsBucket())
                .recordSecurityEvent(eventCode);
        persistDelta(securityEventDelta(bucketKey(now), eventCode));
    }

    public OperationsMetricsSnapshot snapshot(OperationsWindow window) {
        long now = clock.instant().getEpochSecond();
        purge(now);
        long minimumBucket = bucketKey(now - window.seconds());
        Aggregate aggregate = new Aggregate();
        if (store.sharedReads()) {
            List<OperationsMetricsStore.Bucket> sharedBuckets = store.load();
            if (!"DEGRADED".equals(store.status())) {
                sharedBuckets.forEach(bucket -> {
                    if (bucket.bucketKey() >= minimumBucket && bucket.bucketKey() <= bucketKey(now)) {
                        aggregate.add(bucket);
                    }
                });
            } else {
                addLocalBuckets(aggregate, minimumBucket, now);
            }
        } else {
            addLocalBuckets(aggregate, minimumBucket, now);
        }
        String persistence = store.status();
        return new OperationsMetricsSnapshot(window, Instant.ofEpochSecond(now),
                new OperationsMetricsSnapshot.Health("DEGRADED".equals(persistence) ? "DEGRADED" : "UP",
                        packageStorage == null || packageStorage.isBlank() ? "NOT_CONFIGURED" : "CONFIGURED",
                        invocationPersistence ? "ENABLED" : "DISABLED", persistence),
                new OperationsMetricsSnapshot.RequestCounts(aggregate.total, aggregate.successes,
                        aggregate.clientErrors, aggregate.serverErrors),
                new OperationsMetricsSnapshot.LatencyMetrics(
                        aggregate.percentile(50), aggregate.percentile(95), aggregate.maxMs),
                Map.copyOf(new TreeMap<>(aggregate.securityEvents)));
    }

    public void clear() {
        buckets.clear();
        persist();
    }

    private void restore() {
        store.load().stream()
                .filter(bucket -> bucket.latencyCounts().length == LATENCY_UPPER_BOUNDS.length)
                .forEach(bucket -> buckets.put(bucket.bucketKey(), MetricsBucket.restore(bucket)));
        purge(clock.instant().getEpochSecond());
    }

    private void persist() {
        List<OperationsMetricsStore.Bucket> persisted = new ArrayList<>();
        buckets.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                persisted.add(entry.getValue().persisted(entry.getKey())));
        store.save(persisted);
    }

    private void persistDelta(OperationsMetricsStore.Bucket delta) {
        if (store.sharedReads()) {
            store.merge(List.of(delta));
        } else {
            persist();
        }
    }

    private static OperationsMetricsStore createStore(String metricsStorage, ObjectMapper objectMapper,
                                                       StringRedisTemplate redisTemplate, String redisKey,
                                                       long redisTtlSeconds) {
        if ("redis".equalsIgnoreCase(metricsStorage)) {
            if (redisTemplate == null) {
                throw new IllegalStateException("Redis metrics storage requires a Redis connection");
            }
            return new RedisOperationsMetricsStore(redisTemplate, redisKey, redisTtlSeconds);
        }
        if (metricsStorage == null || metricsStorage.isBlank() || "memory".equalsIgnoreCase(metricsStorage)) {
            return new MemoryOperationsMetricsStore();
        }
        return new JsonOperationsMetricsStore(Path.of(metricsStorage), objectMapper);
    }

    private void addLocalBuckets(Aggregate aggregate, long minimumBucket, long now) {
        buckets.forEach((key, bucket) -> {
            if (key >= minimumBucket && key <= bucketKey(now)) {
                aggregate.add(bucket.snapshot());
            }
        });
    }

    private OperationsMetricsStore.Bucket requestDelta(long bucketKey, int status, long durationMs) {
        long[] latencyCounts = new long[LATENCY_UPPER_BOUNDS.length];
        latencyCounts[MetricsBucket.latencyBucket(durationMs)] = 1;
        long clientErrors = status >= 400 && status < 500 ? 1 : 0;
        long serverErrors = status >= 500 ? 1 : 0;
        long successes = status < 400 ? 1 : 0;
        return new OperationsMetricsStore.Bucket(bucketKey, 1, successes, clientErrors, serverErrors,
                durationMs, latencyCounts, Map.of());
    }

    private OperationsMetricsStore.Bucket securityEventDelta(long bucketKey, String eventCode) {
        return new OperationsMetricsStore.Bucket(bucketKey, 0, 0, 0, 0, 0,
                new long[LATENCY_UPPER_BOUNDS.length], Map.of(eventCode, 1L));
    }

    private long bucketKey(long epochSeconds) {
        return Math.floorDiv(epochSeconds, BUCKET_SECONDS);
    }

    private void purge(long nowEpochSeconds) {
        long oldest = bucketKey(nowEpochSeconds) - RETAINED_BUCKETS + 1;
        buckets.keySet().removeIf(key -> key < oldest);
    }

    private static final class MemoryOperationsMetricsStore implements OperationsMetricsStore {
        @Override
        public List<Bucket> load() {
            return List.of();
        }

        @Override
        public void save(List<Bucket> buckets) {
            // Tests and explicitly memory-configured instances intentionally do not persist.
        }

        @Override
        public String status() {
            return "DISABLED";
        }
    }

    private static final class MetricsBucket {
        private final LongAdder total = new LongAdder();
        private final LongAdder successes = new LongAdder();
        private final LongAdder clientErrors = new LongAdder();
        private final LongAdder serverErrors = new LongAdder();
        private final AtomicLong maxMs = new AtomicLong();
        private final AtomicLongArray latencyHistogram = new AtomicLongArray(LATENCY_UPPER_BOUNDS.length);
        private final ConcurrentHashMap<String, LongAdder> securityEvents = new ConcurrentHashMap<>();

        private void recordRequest(int status, long durationMs) {
            total.increment();
            if (status >= 500) {
                serverErrors.increment();
            } else if (status >= 400) {
                clientErrors.increment();
            } else {
                successes.increment();
            }
            maxMs.updateAndGet(current -> Math.max(current, durationMs));
            latencyHistogram.incrementAndGet(latencyBucket(durationMs));
        }

        private void recordSecurityEvent(String eventCode) {
            securityEvents.computeIfAbsent(eventCode, ignored -> new LongAdder()).increment();
        }

        private BucketSnapshot snapshot() {
            long[] latencyCounts = new long[LATENCY_UPPER_BOUNDS.length];
            for (int index = 0; index < latencyCounts.length; index++) {
                latencyCounts[index] = latencyHistogram.get(index);
            }
            Map<String, Long> security = new TreeMap<>();
            securityEvents.forEach((key, value) -> security.put(key, value.sum()));
            return new BucketSnapshot(total.sum(), successes.sum(), clientErrors.sum(), serverErrors.sum(),
                    maxMs.get(), latencyCounts, security);
        }

        private OperationsMetricsStore.Bucket persisted(long bucketKey) {
            BucketSnapshot snapshot = snapshot();
            return new OperationsMetricsStore.Bucket(bucketKey, snapshot.total(), snapshot.successes(),
                    snapshot.clientErrors(), snapshot.serverErrors(), snapshot.maxMs(), snapshot.latencyCounts(),
                    snapshot.securityEvents());
        }

        private static MetricsBucket restore(OperationsMetricsStore.Bucket bucket) {
            MetricsBucket restored = new MetricsBucket();
            restored.total.add(bucket.total());
            restored.successes.add(bucket.successes());
            restored.clientErrors.add(bucket.clientErrors());
            restored.serverErrors.add(bucket.serverErrors());
            restored.maxMs.set(bucket.maxMs());
            long[] latencyCounts = bucket.latencyCounts();
            for (int index = 0; index < latencyCounts.length; index++) {
                restored.latencyHistogram.set(index, latencyCounts[index]);
            }
            bucket.securityEvents().forEach((key, value) -> {
                if (SECURITY_EVENTS.contains(key)) {
                    LongAdder adder = new LongAdder();
                    adder.add(value);
                    restored.securityEvents.put(key, adder);
                }
            });
            return restored;
        }

        private static int latencyBucket(long durationMs) {
            for (int index = 0; index < LATENCY_UPPER_BOUNDS.length; index++) {
                if (durationMs <= LATENCY_UPPER_BOUNDS[index]) {
                    return index;
                }
            }
            return LATENCY_UPPER_BOUNDS.length - 1;
        }
    }

    private record BucketSnapshot(long total, long successes, long clientErrors, long serverErrors,
                                  long maxMs, long[] latencyCounts, Map<String, Long> securityEvents) {
    }

    private static final class Aggregate {
        private long total;
        private long successes;
        private long clientErrors;
        private long serverErrors;
        private long maxMs;
        private final long[] latencyCounts = new long[LATENCY_UPPER_BOUNDS.length];
        private final Map<String, Long> securityEvents = new TreeMap<>();

        private void add(BucketSnapshot snapshot) {
            total += snapshot.total();
            successes += snapshot.successes();
            clientErrors += snapshot.clientErrors();
            serverErrors += snapshot.serverErrors();
            maxMs = Math.max(maxMs, snapshot.maxMs());
            for (int index = 0; index < latencyCounts.length; index++) {
                latencyCounts[index] += snapshot.latencyCounts()[index];
            }
            snapshot.securityEvents().forEach((key, value) ->
                    securityEvents.merge(key, value, Long::sum));
        }

        private void add(OperationsMetricsStore.Bucket bucket) {
            total += bucket.total();
            successes += bucket.successes();
            clientErrors += bucket.clientErrors();
            serverErrors += bucket.serverErrors();
            maxMs = Math.max(maxMs, bucket.maxMs());
            long[] counts = bucket.latencyCounts();
            for (int index = 0; index < latencyCounts.length && index < counts.length; index++) {
                latencyCounts[index] += counts[index];
            }
            bucket.securityEvents().forEach((key, value) -> securityEvents.merge(key, value, Long::sum));
        }

        private long percentile(int percentile) {
            if (total == 0) {
                return 0;
            }
            long target = Math.max(1, (total * percentile + 99) / 100);
            long seen = 0;
            for (int index = 0; index < latencyCounts.length; index++) {
                seen += latencyCounts[index];
                if (seen >= target) {
                    return LATENCY_UPPER_BOUNDS[index];
                }
            }
            return LATENCY_UPPER_BOUNDS[LATENCY_UPPER_BOUNDS.length - 1];
        }
    }
}
