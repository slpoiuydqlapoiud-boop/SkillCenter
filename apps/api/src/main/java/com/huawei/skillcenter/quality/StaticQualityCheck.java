package com.huawei.skillcenter.quality;

public record StaticQualityCheck(String ruleId, boolean passed, String message) {
}
