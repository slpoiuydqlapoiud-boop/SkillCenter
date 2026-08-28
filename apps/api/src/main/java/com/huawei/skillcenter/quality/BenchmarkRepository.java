package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Persistence port for immutable Benchmark evidence. */
public interface BenchmarkRepository {
    List<BenchmarkResult> findAll(String skillId);

    BenchmarkResult findByExperimentId(String experimentId);

    BenchmarkResult add(BenchmarkResult result);

    long countBefore(Instant cutoff, Set<String> protectedBenchmarkIds);

    int deleteBefore(Instant cutoff, Set<String> protectedBenchmarkIds);
}
