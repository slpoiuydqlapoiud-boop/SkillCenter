package com.huawei.skillcenter.quality;

public class OptimizationExperimentAssessmentNotFoundException extends RuntimeException {
    public OptimizationExperimentAssessmentNotFoundException(String assessmentId) {
        super("Assessment not found: " + assessmentId);
    }
}
