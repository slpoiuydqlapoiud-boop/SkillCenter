package com.huawei.skillcenter.quality;

public record QualityRuleSet(
        String id,
        String version,
        int minScore,
        double minPassRate,
        int minStaticScore
) {
    public QualityRuleSet {
        if (id == null || id.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("rule id and version are required");
        }
        if (minScore < 0 || minScore > 100 || minStaticScore < 0 || minStaticScore > 100
                || minPassRate < 0 || minPassRate > 1) {
            throw new IllegalArgumentException("quality thresholds are out of range");
        }
    }
}
