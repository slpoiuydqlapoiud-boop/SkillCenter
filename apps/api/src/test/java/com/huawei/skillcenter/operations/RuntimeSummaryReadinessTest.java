package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSummaryReadinessTest {
    @Test
    void jsonRuntimeSummaryStoreIsExplicitlyMarkedAsSingleInstanceDegraded() {
        RuntimeSummaryReadiness readiness = new RuntimeSummaryStore().readiness();

        assertThat(readiness.backend()).isEqualTo("json");
        assertThat(readiness.status()).isEqualTo("DEGRADED");
        assertThat(readiness.reasonCode()).isEqualTo("RUNTIME_SUMMARY_JSON_ONLY");
        assertThat(readiness.summary()).doesNotContain("endpoint").doesNotContain("credential");
    }
}
