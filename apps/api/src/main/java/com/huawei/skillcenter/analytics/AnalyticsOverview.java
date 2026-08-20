package com.huawei.skillcenter.analytics;

import java.util.List;

public record AnalyticsOverview(
        Kpis kpis,
        List<DayPoint> series,
        List<TopSkill> topSkills,
        List<VersionAdoption> versionAdoption,
        List<ErrorBreakdown> errorBreakdown,
        Latency latency,
        DataQuality dataQuality
) {
    public AnalyticsOverview {
        series = List.copyOf(series == null ? List.of() : series);
        topSkills = List.copyOf(topSkills == null ? List.of() : topSkills);
        versionAdoption = List.copyOf(versionAdoption == null ? List.of() : versionAdoption);
        errorBreakdown = List.copyOf(errorBreakdown == null ? List.of() : errorBreakdown);
        latency = latency == null ? Latency.empty() : latency;
        dataQuality = dataQuality == null ? DataQuality.empty() : dataQuality;
    }

    public AnalyticsOverview(Kpis kpis, List<DayPoint> series, List<TopSkill> topSkills) {
        this(kpis, series, topSkills, List.of(), List.of(), Latency.empty(), DataQuality.empty());
    }

    public record Kpis(long calls, long successfulCalls, double successRate, long activeSkills,
                       long activeUsers, long activeTeams, long currentInstallations,
                       double installationSuccessRate) {
        public Kpis(long calls, long successfulCalls, double successRate, long activeSkills) {
            this(calls, successfulCalls, successRate, activeSkills, 0, 0, 0, 0);
        }
    }

    public record DayPoint(String day, long calls, double successRate, long activeUsers, long installations) {
        public DayPoint(String day, long calls, double successRate) {
            this(day, calls, successRate, 0, 0);
        }
    }

    public record TopSkill(String id, String name, long calls, double successRate) {}

    public record VersionAdoption(String skillId, String version, long calls, double percentage) {}

    public record ErrorBreakdown(String errorCode, long count, double percentage) {}

    public record Latency(long sampleCount, long p50Ms, long p95Ms, long maxMs) {
        public static Latency empty() {
            return new Latency(0, 0, 0, 0);
        }
    }

    public record DataQuality(long accepted, long duplicates, long rejected,
                              String earliestOccurredAt, String latestOccurredAt, boolean hasBackfill) {
        public static DataQuality empty() {
            return new DataQuality(0, 0, 0, null, null, false);
        }
    }
}
