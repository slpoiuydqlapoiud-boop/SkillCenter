package com.huawei.skillcenter.operations;

import java.util.List;
import java.util.Map;

public interface OperationsMetricsStore {
    List<Bucket> load();

    void save(List<Bucket> buckets);

    default void merge(List<Bucket> buckets) {
        save(buckets);
    }

    default boolean sharedReads() {
        return false;
    }

    String status();

    static Bucket combine(Bucket current, Bucket delta) {
        int length = Math.max(current.latencyCounts().length, delta.latencyCounts().length);
        long[] latencyCounts = new long[length];
        long[] currentCounts = current.latencyCounts();
        long[] deltaCounts = delta.latencyCounts();
        for (int index = 0; index < length; index++) {
            long currentValue = index < currentCounts.length ? currentCounts[index] : 0;
            long deltaValue = index < deltaCounts.length ? deltaCounts[index] : 0;
            latencyCounts[index] = currentValue + deltaValue;
        }
        Map<String, Long> securityEvents = new java.util.TreeMap<>(current.securityEvents());
        delta.securityEvents().forEach((key, value) -> securityEvents.merge(key, value, Long::sum));
        return new Bucket(current.bucketKey(), current.total() + delta.total(),
                current.successes() + delta.successes(), current.clientErrors() + delta.clientErrors(),
                current.serverErrors() + delta.serverErrors(), Math.max(current.maxMs(), delta.maxMs()),
                latencyCounts, securityEvents);
    }

    record Bucket(long bucketKey, long total, long successes, long clientErrors, long serverErrors,
                  long maxMs, long[] latencyCounts, Map<String, Long> securityEvents) {
        public Bucket {
            latencyCounts = latencyCounts == null ? new long[0] : latencyCounts.clone();
            securityEvents = securityEvents == null ? Map.of() : Map.copyOf(securityEvents);
        }

        @Override
        public long[] latencyCounts() {
            return latencyCounts.clone();
        }
    }
}
