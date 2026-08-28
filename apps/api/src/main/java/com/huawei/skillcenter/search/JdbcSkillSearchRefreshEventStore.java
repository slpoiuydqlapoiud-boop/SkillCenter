package com.huawei.skillcenter.search;

import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** PostgreSQL refresh journal with deterministic event-key idempotency. */
@Conditional(SkillSearchBackendCondition.Postgresql.class)
public final class JdbcSkillSearchRefreshEventStore implements SkillSearchRefreshEventStore {
    private static final String TABLE = "skill_search_refresh_events";
    private static final String CONSUMER_TABLE = "skill_search_refresh_event_consumers";
    private static final String MAINTENANCE_LOCK_KEY = "skillcenter:skill-search-refresh-journal-maintenance";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcSkillSearchRefreshEventStore(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    public JdbcSkillSearchRefreshEventStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    public String backend() {
        return "postgresql";
    }

    @Override
    public void append(SkillSearchRefreshEvent event) {
        if (event == null) throw new IllegalArgumentException("event is required");
        try {
            jdbc.update("insert into " + TABLE
                            + " (event_id, skill_id, source_revision, reason_code, created_at)"
                            + " values (?, ?, ?, ?, ?)"
                            + " on conflict (event_id) do nothing",
                    event.eventKey(), event.skillId(), event.sourceRevision(), event.reasonCode(),
                    Timestamp.from(java.time.Instant.now()));
        } catch (RuntimeException exception) {
            throw new SkillSearchIndexPersistenceException(exception);
        }
    }

    @Override
    public List<StoredSkillSearchRefreshEvent> findAfter(long sequence, int limit) {
        if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        try {
            return jdbc.query("select event_seq, skill_id, source_revision, reason_code from " + TABLE
                            + " where event_seq > ? order by event_seq asc limit ?",
                    this::map, sequence, limit);
        } catch (RuntimeException exception) {
            throw new SkillSearchIndexPersistenceException(exception);
        }
    }

    @Override
    public long loadCursor(String consumerId) {
        String boundedConsumerId = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        try {
            Long sequence = transactions == null
                    ? loadCursorInternal(boundedConsumerId, false)
                    : transactions.execute(status -> loadCursorInternal(boundedConsumerId, true));
            return sequence == null ? 0L : sequence;
        } catch (RuntimeException exception) {
            throw new SkillSearchIndexPersistenceException(exception);
        }
    }

    @Override
    public void saveCursor(String consumerId, long sequence) {
        String boundedConsumerId = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
        try {
            jdbc.update("insert into " + CONSUMER_TABLE
                    + " (consumer_id, last_event_seq, updated_at) values (?, ?, ?)"
                    + " on conflict (consumer_id) do update set"
                    + " last_event_seq = greatest(" + CONSUMER_TABLE + ".last_event_seq, excluded.last_event_seq),"
                    + " updated_at = excluded.updated_at",
                    boundedConsumerId, sequence, Timestamp.from(java.time.Instant.now()));
        } catch (RuntimeException exception) {
            throw new SkillSearchIndexPersistenceException(exception);
        }
    }

    @Override
    public SkillSearchRefreshCleanupResult purgeConsumedBefore(Instant cutoff, int limit) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        try {
            if (transactions == null) {
                return purgeConsumedBeforeInternal(cutoff, limit, false);
            }
            SkillSearchRefreshCleanupResult result = transactions.execute(status ->
                    purgeConsumedBeforeInternal(cutoff, limit, true));
            if (result == null) throw new IllegalStateException("cleanup transaction returned no result");
            return result;
        } catch (RuntimeException exception) {
            throw new SkillSearchIndexPersistenceException(exception);
        }
    }

    private Long loadCursorInternal(String consumerId, boolean lock) {
        if (lock) acquireMaintenanceLock();
        jdbc.update("insert into " + CONSUMER_TABLE
                + " (consumer_id, last_event_seq, updated_at) values (?, 0, ?)"
                + " on conflict (consumer_id) do nothing",
                consumerId, Timestamp.from(Instant.now()));
        return jdbc.queryForObject("select last_event_seq from " + CONSUMER_TABLE
                + " where consumer_id = ?", Long.class, consumerId);
    }

    private SkillSearchRefreshCleanupResult purgeConsumedBeforeInternal(Instant cutoff, int limit, boolean lock) {
        if (lock) acquireMaintenanceLock();
        Long watermark = jdbc.queryForObject("select min(last_event_seq) from " + CONSUMER_TABLE,
                Long.class, new Object[0]);
        long consumerWatermark = watermark == null ? 0L : watermark;
        if (watermark == null) {
            return new SkillSearchRefreshCleanupResult(0, 0L, cutoff);
        }
        int deleted = jdbc.update("with eligible as ("
                        + " select event_seq from " + TABLE
                        + " where created_at < ? and event_seq <= ?"
                        + " order by event_seq asc limit ?"
                        + ") delete from " + TABLE + " events using eligible"
                        + " where events.event_seq = eligible.event_seq",
                Timestamp.from(cutoff), consumerWatermark, limit);
        return new SkillSearchRefreshCleanupResult(deleted, consumerWatermark, cutoff);
    }

    private void acquireMaintenanceLock() {
        jdbc.update("select pg_advisory_xact_lock(hashtext(?))", MAINTENANCE_LOCK_KEY);
    }

    private StoredSkillSearchRefreshEvent map(ResultSet resultSet, int ignored) throws SQLException {
        return new StoredSkillSearchRefreshEvent(resultSet.getLong("event_seq"),
                new SkillSearchRefreshEvent(resultSet.getString("skill_id"),
                        resultSet.getLong("source_revision"), resultSet.getString("reason_code")));
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
