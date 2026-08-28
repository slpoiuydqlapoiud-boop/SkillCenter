package com.huawei.skillcenter.quality;

public record EvaluationResult(boolean passed, int score, String reason) {
}
