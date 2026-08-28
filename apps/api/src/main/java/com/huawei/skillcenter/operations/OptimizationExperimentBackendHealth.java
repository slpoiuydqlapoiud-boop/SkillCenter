package com.huawei.skillcenter.operations;

/** Read-only health port for the optimization experiment persistence backend. */
public interface OptimizationExperimentBackendHealth {
    OptimizationExperimentBackendReadiness readiness();
}
