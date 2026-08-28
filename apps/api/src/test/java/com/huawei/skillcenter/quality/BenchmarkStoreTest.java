package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsBenchmarkResultsAndFiltersBySkill() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path state = tempDir.resolve("benchmarks.json");
        BenchmarkResult result = new BenchmarkResult("benchmark-1", "skill-a", "1.0.0", "1.1.0",
                "24h", "mock", "IMPROVED", Instant.parse("2026-08-21T01:02:03Z"),
                new QualityComparison("skill-a", "1.0.0", "1.1.0", true, "COMPARABLE", "ok", null, null,
                        new QualityComparison.Delta(1, 0, 0, 0, -1, 0)));
        BenchmarkStore store = new BenchmarkStore(state, mapper);

        store.add(result);

        assertThat(new BenchmarkStore(state, mapper).findAll("skill-a")).containsExactly(result);
        assertThat(store.findAll("skill-b")).isEmpty();
    }

    @Test
    void countsAndDeletesBenchmarksBeforeRetentionCutoff() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path state = tempDir.resolve("benchmarks-retention.json");
        BenchmarkStore store = new BenchmarkStore(state, mapper);
        Instant cutoff = Instant.parse("2026-08-01T00:00:00Z");
        store.add(new BenchmarkResult("old", "skill-a", "1.0.0", "1.1.0", "24h", "mock", "REGRESSED",
                cutoff.minusSeconds(1), null));
        store.add(new BenchmarkResult("new", "skill-a", "1.0.0", "1.1.0", "24h", "mock", "IMPROVED",
                cutoff.plusSeconds(1), null));

        assertThat(store.countBefore(cutoff)).isEqualTo(1);
        assertThat(store.deleteBefore(cutoff)).isEqualTo(1);
        assertThat(store.findAll("skill-a")).extracting(BenchmarkResult::benchmarkId).containsExactly("new");
        assertThat(store.deleteBefore(cutoff)).isZero();
    }

    @Test
    void preservesBenchmarksInRetentionProtectionSet() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path state = tempDir.resolve("benchmarks-protected.json");
        BenchmarkStore store = new BenchmarkStore(state, mapper);
        Instant cutoff = Instant.parse("2026-08-01T00:00:00Z");
        store.add(new BenchmarkResult("protected", "skill-a", "1.0.0", "1.1.0", "24h", "mock", "IMPROVED",
                cutoff.minusSeconds(1), null));
        store.add(new BenchmarkResult("expired", "skill-a", "1.0.0", "1.1.0", "24h", "mock", "REGRESSED",
                cutoff.minusSeconds(2), null));

        assertThat(store.countBefore(cutoff, java.util.Set.of("protected"))).isEqualTo(1);
        assertThat(store.deleteBefore(cutoff, java.util.Set.of("protected"))).isEqualTo(1);
        assertThat(store.findAll("skill-a")).extracting(BenchmarkResult::benchmarkId)
                .containsExactly("protected");
    }

    @Test
    void rejectsDuplicateBenchmarkIds() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        BenchmarkStore store = new BenchmarkStore(tempDir.resolve("benchmarks-duplicate.json"), mapper);
        BenchmarkResult result = new BenchmarkResult("duplicate", "skill-a", "1.0.0", "1.1.0",
                "24h", "mock", "IMPROVED", Instant.now(), null);
        store.add(result);

        assertThatThrownBy(() -> store.add(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("benchmarkId");
    }

    @Test
    void rejectsBenchmarkWhoseComparisonContextDiffers() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        BenchmarkStore store = new BenchmarkStore(tempDir.resolve("benchmarks-context.json"), mapper);
        BenchmarkResult result = new BenchmarkResult("context-mismatch", "skill-a", "1.0.0", "1.1.0",
                "24h", "mock", "IMPROVED", Instant.now(),
                new QualityComparison("skill-b", "1.0.0", "1.1.0", true, "COMPARABLE", "", null, null, null));

        assertThatThrownBy(() -> store.add(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comparison");
    }
}
