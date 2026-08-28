package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for post-release experiment assessment evidence. */
public interface OptimizationExperimentAssessmentRepository {
    List<OptimizationExperimentAssessment> findAll(String experimentId);
    Optional<OptimizationExperimentAssessment> find(String assessmentId);
    long countBefore(Instant cutoff);
    long deleteBefore(Instant cutoff);
    OptimizationExperimentAssessment create(OptimizationExperimentAssessment value);
}
