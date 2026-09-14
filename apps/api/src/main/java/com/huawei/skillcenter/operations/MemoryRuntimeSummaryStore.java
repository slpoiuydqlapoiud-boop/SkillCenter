package com.huawei.skillcenter.operations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** In-memory runtime summary repository for the department single-instance profile. */
@Component
@ConditionalOnProperty(name = "skill-center.runtime-summary-backend", havingValue = "memory")
public class MemoryRuntimeSummaryStore implements RuntimeSummaryRepository, RuntimeSummaryBackendHealth {
    private final RuntimeSummaryStore delegate = new RuntimeSummaryStore();

    @Override
    public boolean putIfAbsent(RuntimeSummary summary) {
        return delegate.putIfAbsent(summary);
    }

    @Override
    public List<RuntimeSummary> findAll() {
        return delegate.findAll();
    }

    @Override
    public long countBefore(Instant cutoff) {
        return delegate.countBefore(cutoff);
    }

    @Override
    public int deleteBefore(Instant cutoff) {
        return delegate.deleteBefore(cutoff);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public RuntimeSummaryReadiness readiness() {
        return new RuntimeSummaryReadiness("memory", "DEGRADED", "RUNTIME_SUMMARY_MEMORY_ONLY",
                "运行摘要使用进程内内存，适用于部门单实例运行；重启后不保留历史摘要");
    }
}
