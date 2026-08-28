package com.huawei.skillcenter.operations;

/** Read-only health projection for the configured Benchmark evidence backend. */
public interface BenchmarkBackendHealth {
    BenchmarkBackendReadiness readiness();
}
