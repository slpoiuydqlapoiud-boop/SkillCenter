package com.huawei.skillcenter.operations;

/** Read-only health port for the optimization work-item persistence backend. */
public interface OptimizationWorkItemBackendHealth {
    OptimizationWorkItemBackendReadiness readiness();
}
