package com.huawei.skillcenter.quality;

public class OptimizationExperimentNotFoundException extends RuntimeException {
    public OptimizationExperimentNotFoundException(String experimentId) {
        super("Optimization experiment not found: " + experimentId);
    }
}
