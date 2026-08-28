package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.RuntimeOperationsWindow;

public record OptimizationExperimentBenchmarkRequest(String window) {
    public OptimizationExperimentBenchmarkRequest {
        window = window == null || window.isBlank() ? RuntimeOperationsWindow.TWENTY_FOUR_HOURS.label() : window.trim();
        RuntimeOperationsWindow.parse(window);
    }
}
