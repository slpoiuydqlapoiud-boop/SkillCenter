package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.quality.OptimizationWorkItem;
import com.huawei.skillcenter.quality.OptimizationWorkItemRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationWorkItemStalenessServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void reportsStaleActiveWorkItemsWithStableBreakdownsAndExcludesTerminalItems() {
        OptimizationWorkItem stale = item("work-stale", "OPEN", "HIGH", NOW.minusSeconds(8 * 24 * 3600L));
        OptimizationWorkItem fresh = item("work-fresh", "IN_PROGRESS", "MEDIUM", NOW.minusSeconds(2 * 24 * 3600L));
        OptimizationWorkItem completed = item("work-completed", "COMPLETED", "HIGH", NOW.minusSeconds(30 * 24 * 3600L));
        OptimizationWorkItemStalenessService service = new OptimizationWorkItemStalenessService(
                repository(List.of(stale, fresh, completed)), 7 * 24 * 3600L, fixedClock());

        OptimizationWorkItemStaleness health = service.health();

        assertThat(health.status()).isEqualTo("DEGRADED");
        assertThat(health.reasonCode()).isEqualTo("OPTIMIZATION_WORK_ITEMS_STALE");
        assertThat(health.activeCount()).isEqualTo(2);
        assertThat(health.staleCount()).isEqualTo(1);
        assertThat(health.staleByStatus()).containsEntry("OPEN", 1);
        assertThat(health.staleByOwner()).containsEntry("owner-work-stale", 1);
        assertThat(health.staleBySeverity()).containsEntry("HIGH", 1);
        assertThat(health.staleItems()).extracting(OptimizationWorkItemStaleItem::workItemId)
                .containsExactly("work-stale");
    }

    @Test
    void reportsHealthyWhenAllActiveWorkItemsAreWithinThreshold() {
        OptimizationWorkItem fresh = item("work-fresh", "READY_FOR_EVALUATION", "MEDIUM", NOW.minusSeconds(60));
        OptimizationWorkItemStalenessService service = new OptimizationWorkItemStalenessService(
                repository(List.of(fresh)), 7 * 24 * 3600L, fixedClock());

        OptimizationWorkItemStaleness health = service.health();

        assertThat(health.status()).isEqualTo("HEALTHY");
        assertThat(health.reasonCode()).isEqualTo("OPTIMIZATION_WORK_ITEMS_HEALTHY");
        assertThat(health.staleCount()).isZero();
        assertThat(health.staleItems()).isEmpty();
    }

    @Test
    void convertsRepositoryFailureToSafeNotReadyHealth() {
        OptimizationWorkItemRepository repository = new OptimizationWorkItemRepository() {
            @Override
            public List<OptimizationWorkItem> findAll(String skillId, String status, String ownerId, String sourceVersion) {
                throw new IllegalStateException("database password=secret");
            }

            @Override
            public Optional<OptimizationWorkItem> find(String workItemId) { return Optional.empty(); }

            @Override
            public OptimizationWorkItem create(OptimizationWorkItem value) { return value; }

            @Override
            public OptimizationWorkItem replace(OptimizationWorkItem value) { return value; }
        };

        OptimizationWorkItemStaleness health = new OptimizationWorkItemStalenessService(
                repository, 7 * 24 * 3600L, fixedClock()).health();

        assertThat(health.status()).isEqualTo("NOT_READY");
        assertThat(health.reasonCode()).isEqualTo("OPTIMIZATION_WORK_ITEM_HEALTH_UNAVAILABLE");
        assertThat(health.toString()).doesNotContain("secret");
    }

    private static OptimizationWorkItemRepository repository(List<OptimizationWorkItem> values) {
        return new OptimizationWorkItemRepository() {
            @Override
            public List<OptimizationWorkItem> findAll(String skillId, String status, String ownerId, String sourceVersion) {
                return values;
            }

            @Override
            public Optional<OptimizationWorkItem> find(String workItemId) { return Optional.empty(); }

            @Override
            public OptimizationWorkItem create(OptimizationWorkItem value) { return value; }

            @Override
            public OptimizationWorkItem replace(OptimizationWorkItem value) { return value; }
        };
    }

    private static OptimizationWorkItem item(String id, String status, String severity, Instant updatedAt) {
        Instant createdAt = updatedAt.minusSeconds(3600);
        boolean terminal = "COMPLETED".equals(status);
        boolean candidateRequired = terminal || "READY_FOR_EVALUATION".equals(status);
        return new OptimizationWorkItem(id, "skill-a", "1.0.0", "suggestion-" + id,
                "建议 " + id, "LATENCY", severity, List.of("p95Ms=1200"), "验证改进假设",
                "owner-" + id, status, candidateRequired ? "1.1.0" : "", terminal ? "BENCHMARK" : "NONE",
                terminal ? "benchmark-" + id : "", terminal ? "done" : "",
                "production", "runtime-a", "mcp-a", "llm-a", "suite-a", "suite-v1",
                "admin", createdAt, "admin", updatedAt);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
