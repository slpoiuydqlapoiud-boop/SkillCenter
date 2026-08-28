package com.huawei.skillcenter.quality;

import java.util.List;
import java.util.Optional;

/** Persistence port for the optimization work-item control plane. */
public interface OptimizationWorkItemRepository {
    List<OptimizationWorkItem> findAll(String skillId, String status, String ownerId, String sourceVersion);

    Optional<OptimizationWorkItem> find(String workItemId);

    OptimizationWorkItem create(OptimizationWorkItem value);

    OptimizationWorkItem replace(OptimizationWorkItem value);
}
