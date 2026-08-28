package com.huawei.skillcenter.quality;

import java.util.List;

public record StaticQualityReport(int score, int totalChecks, int passedChecks, List<StaticQualityCheck> checks) {
    public StaticQualityReport {
        checks = List.copyOf(checks);
    }
}
