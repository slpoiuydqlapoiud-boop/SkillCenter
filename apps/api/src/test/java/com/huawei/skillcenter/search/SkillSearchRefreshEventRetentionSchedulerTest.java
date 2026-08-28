package com.huawei.skillcenter.search;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillSearchRefreshEventRetentionSchedulerTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void runOnceUsesRetentionCutoffAndConfiguredBatchSize() {
        SkillSearchRefreshEventStore store = mock(SkillSearchRefreshEventStore.class);
        SkillSearchRefreshCleanupResult expected = new SkillSearchRefreshCleanupResult(
                4, 21L, Instant.parse("2026-07-29T12:00:00Z"));
        when(store.purgeConsumedBefore(expected.cutoff(), 250)).thenReturn(expected);
        SkillSearchRefreshEventRetentionScheduler scheduler = new SkillSearchRefreshEventRetentionScheduler(
                store, 30, 250, NOW);

        assertThat(scheduler.runOnce()).isEqualTo(expected);
        verify(store).purgeConsumedBefore(expected.cutoff(), 250);
    }

    @Test
    void invalidRetentionConfigurationFailsClosed() {
        SkillSearchRefreshEventStore store = mock(SkillSearchRefreshEventStore.class);

        assertThatThrownBy(() -> new SkillSearchRefreshEventRetentionScheduler(store, 0, 10, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retentionDays must be between 1 and 3650");
        assertThatThrownBy(() -> new SkillSearchRefreshEventRetentionScheduler(store, 30, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("batchSize must be between 1 and 10000");
        assertThatThrownBy(() -> new SkillSearchRefreshEventRetentionScheduler(store, 30, 100, NOW, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cleanupIntervalMs must be between 1000 and 86400000");
        assertThatThrownBy(() -> new SkillSearchRefreshEventRetentionScheduler(store, 30, 100, NOW, 1000, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cleanupInitialDelayMs must be between 0 and 86400000");
    }

    @Test
    void scheduledExecutionContainsStoreFailure() {
        SkillSearchRefreshEventStore store = mock(SkillSearchRefreshEventStore.class);
        when(store.purgeConsumedBefore(any(Instant.class), eq(100)))
                .thenThrow(new IllegalStateException("temporary"));
        SkillSearchRefreshEventRetentionScheduler scheduler = new SkillSearchRefreshEventRetentionScheduler(
                store, 30, 100, NOW);

        org.assertj.core.api.Assertions.assertThatCode(scheduler::runScheduled).doesNotThrowAnyException();
    }

    @Test
    void configurationDoesNotCreateRetentionSchedulerBeforeV19() {
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "18", null));

        assertThatThrownBy(() -> new SkillSearchCatalogConfiguration()
                .skillSearchRefreshEventRetentionScheduler(mock(SkillSearchRefreshEventStore.class), persistence,
                        30, 100, 3600000L, 3600000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("search refresh retention requires READY PostgreSQL V19 schema");
    }
}
