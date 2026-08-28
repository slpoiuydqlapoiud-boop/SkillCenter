package com.huawei.skillcenter.operations;

/** Safe health projection for the configured runtime-summary persistence backend. */
public interface RuntimeSummaryBackendHealth {
    RuntimeSummaryReadiness readiness();
}
