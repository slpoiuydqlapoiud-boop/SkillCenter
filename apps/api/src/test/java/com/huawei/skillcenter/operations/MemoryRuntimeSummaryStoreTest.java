package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRuntimeSummaryStoreTest {
    @Test
    void storesIdempotentSummariesInProcessAndReportsSingleInstanceReadiness() {
        MemoryRuntimeSummaryStore store = new MemoryRuntimeSummaryStore();
        RuntimeSummary summary = new RuntimeSummary("1.0", UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC), "skill-a", "1.0.0", "success", 20,
                null, "production", "team-a", "mock", "trace-a");

        assertThat(store.putIfAbsent(summary)).isFalse();
        assertThat(store.putIfAbsent(summary)).isTrue();
        assertThat(store.findAll()).containsExactly(summary);
        assertThat(store.readiness()).isEqualTo(new RuntimeSummaryReadiness(
                "memory", "DEGRADED", "RUNTIME_SUMMARY_MEMORY_ONLY",
                "运行摘要使用进程内内存，适用于部门单实例运行；重启后不保留历史摘要"));
    }
}
