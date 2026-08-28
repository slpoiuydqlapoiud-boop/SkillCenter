package com.huawei.skillcenter.quality;

import java.util.List;
import java.util.Optional;

/** Persistence port for optimization experiment orchestration records. */
public interface OptimizationExperimentRepository {
    List<OptimizationExperiment> findAll(String skillId, String workItemId, String status);
    Optional<OptimizationExperiment> find(String experimentId);
    Optional<OptimizationExperiment> findActiveByWorkItemId(String workItemId);
    OptimizationExperiment create(OptimizationExperiment value);
    OptimizationExperiment replace(OptimizationExperiment value);
}
