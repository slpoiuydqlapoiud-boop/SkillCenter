package com.huawei.skillcenter.operations;

/** Read-only contract used by production release admission. */
public interface ProductionReadinessGate {
    PlatformReadiness readiness();
}
