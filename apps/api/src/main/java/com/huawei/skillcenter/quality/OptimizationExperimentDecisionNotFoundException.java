package com.huawei.skillcenter.quality;

public class OptimizationExperimentDecisionNotFoundException extends RuntimeException {
    public OptimizationExperimentDecisionNotFoundException(String experimentId) {
        super("Decision not found for optimization experiment: " + experimentId);
    }
}
