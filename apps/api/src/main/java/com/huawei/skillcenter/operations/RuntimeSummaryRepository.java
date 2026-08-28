package com.huawei.skillcenter.operations;

import java.time.Instant;
import java.util.List;

/** Storage port for redacted runtime summaries. Implementations must preserve event-id idempotency. */
public interface RuntimeSummaryRepository {
    boolean putIfAbsent(RuntimeSummary summary);

    List<RuntimeSummary> findAll();

    long countBefore(Instant cutoff);

    int deleteBefore(Instant cutoff);

    void clear();
}
