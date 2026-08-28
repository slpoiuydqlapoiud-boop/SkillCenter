package com.huawei.skillcenter.quality;

import java.util.List;

public record QualityGate(QualityGateStatus status, List<String> reasons) {
    public QualityGate {
        reasons = List.copyOf(reasons);
    }
}
