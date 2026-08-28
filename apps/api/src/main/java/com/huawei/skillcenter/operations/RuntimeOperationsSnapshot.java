package com.huawei.skillcenter.operations;

import java.time.Instant;
import java.util.List;

public record RuntimeOperationsSnapshot(
        String window,
        Instant generatedAt,
        String dataSource,
        Filters filters,
        Totals totals,
        Latency latency,
        List<ErrorCount> errors,
        List<VersionAdoption> versionAdoption,
        List<SourceBreakdown> sources,
        List<TrendPoint> trend
) {
    public RuntimeOperationsSnapshot {
        errors = List.copyOf(errors == null ? List.of() : errors);
        versionAdoption = List.copyOf(versionAdoption == null ? List.of() : versionAdoption);
        sources = List.copyOf(sources == null ? List.of() : sources);
        trend = List.copyOf(trend == null ? List.of() : trend);
        filters = filters == null ? Filters.empty() : filters;
        totals = totals == null ? Totals.empty() : totals;
        latency = latency == null ? Latency.empty() : latency;
    }

    public record Filters(String skillId, String version, String teamId,
                          String runtimeId, String mcpServerId, String llmProviderId) {
        public Filters(String skillId, String version, String teamId) {
            this(skillId, version, teamId, null, null, null);
        }

        public static Filters empty() {
            return new Filters(null, null, null, null, null, null);
        }
    }

    public record Totals(long total, long successes, long failures, long timeouts,
                         long cancellations, double successRate) {
        public static Totals empty() {
            return new Totals(0, 0, 0, 0, 0, 0);
        }
    }

    public record Latency(long sampleCount, long p50Ms, long p95Ms, long maxMs) {
        public static Latency empty() {
            return new Latency(0, 0, 0, 0);
        }
    }

    public record ErrorCount(String errorCode, long count, double percentage) {
    }

    public record VersionAdoption(String version, long calls, double percentage) {
    }

    public record SourceBreakdown(String dataSource, long total, long successes,
                                  long failures, long timeouts, double successRate, long p95Ms) {
    }

    public record TrendPoint(String bucket, long calls, long successes, long failures,
                             long timeouts, double successRate, long p95Ms) {
    }
}
