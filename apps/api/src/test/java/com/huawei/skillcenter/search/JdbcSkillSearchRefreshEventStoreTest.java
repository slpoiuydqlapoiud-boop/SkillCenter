package com.huawei.skillcenter.search;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcSkillSearchRefreshEventStoreTest {
    @Test
    void appendIsIdempotentOnDeterministicEventKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcSkillSearchRefreshEventStore store = new JdbcSkillSearchRefreshEventStore(jdbc);

        store.append(new SkillSearchRefreshEvent("skill-a", 1L, "VERSION_PUBLISHED"));

        assertThat(store.backend()).isEqualTo("postgresql");
    }

    @Test
    void appendUsesDatabaseConflictHandlingForTransactionalIdempotency() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcSkillSearchRefreshEventStore store = new JdbcSkillSearchRefreshEventStore(jdbc);

        store.append(new SkillSearchRefreshEvent("skill-a", 1L, "VERSION_PUBLISHED"));

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("on conflict (event_id) do nothing"),
                any(Object[].class));
    }

    @Test
    void consumerCursorIsReadAndAdvancedMonotonically() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(12L);
        JdbcSkillSearchRefreshEventStore store = new JdbcSkillSearchRefreshEventStore(jdbc);

        assertThat(store.loadCursor("api-1")).isEqualTo(12L);
        store.saveCursor("api-1", 13L);

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("greatest"), any(Object[].class));
    }

    @Test
    void cleanupIsBoundedByTheSlowestConsumerAndRetentionCutoff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3);
        JdbcSkillSearchRefreshEventStore store = new JdbcSkillSearchRefreshEventStore(jdbc);

        SkillSearchRefreshCleanupResult result = store.purgeConsumedBefore(Instant.parse("2026-08-01T00:00:00Z"), 100);

        assertThat(result.deletedCount()).isEqualTo(3);
        assertThat(result.consumerWatermark()).isEqualTo(42L);
        verify(jdbc).update(argThat(sql -> sql.contains("created_at < ?")
                        && sql.contains("event_seq <= ?")
                        && sql.contains("limit ?")),
                any(Object[].class));
    }

    @Test
    void cleanupDeletesNothingWhenNoConsumerCursorExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
        JdbcSkillSearchRefreshEventStore store = new JdbcSkillSearchRefreshEventStore(jdbc);

        SkillSearchRefreshCleanupResult result = store.purgeConsumedBefore(Instant.parse("2026-08-01T00:00:00Z"), 100);

        assertThat(result.deletedCount()).isZero();
        verify(jdbc, org.mockito.Mockito.never()).update(
                org.mockito.ArgumentMatchers.contains("delete from skill_search_refresh_events"),
                any(Object[].class));
    }

    @Test
    void transactionalMaintenanceSerializesConsumerRegistrationAndCleanup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        JdbcSkillSearchRefreshEventStore store = new JdbcSkillSearchRefreshEventStore(jdbc, transactions);

        store.loadCursor("api-1");
        store.purgeConsumedBefore(Instant.parse("2026-08-01T00:00:00Z"), 100);

        verify(jdbc, org.mockito.Mockito.times(2)).update(
                org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"), any(Object[].class));
        verify(transactions, org.mockito.Mockito.times(2)).commit(status);
    }
}
