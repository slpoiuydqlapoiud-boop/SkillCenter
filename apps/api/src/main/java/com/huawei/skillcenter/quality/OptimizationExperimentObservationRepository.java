package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for post-release runtime observation evidence. */
public interface OptimizationExperimentObservationRepository {
    List<OptimizationExperimentObservation> findAll(String experimentId);
    Optional<OptimizationExperimentObservation> find(String observationId);
    long countBefore(Instant cutoff);
    long deleteBefore(Instant cutoff);
    OptimizationExperimentObservation create(OptimizationExperimentObservation value);
}
