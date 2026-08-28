package com.huawei.skillcenter.search;

import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("insert into " + CONSUMER_TABLE
                    + " (consumer_id, last_event_seq, updated_at, status, last_seen_at) values (?, ?, ?, 'ACTIVE', ?)"
                    + " on conflict (consumer_id) do update set"
                    + " last_event_seq = greatest(" + CONSUMER_TABLE + ".last_event_seq, excluded.last_event_seq),"
                    + " updated_at = excluded.updated_at",
                    boundedConsumerId, sequence, now, now);
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
                + " (consumer_id, last_event_seq, updated_at, status, last_seen_at) values (?, 0, ?, 'ACTIVE', ?)"
                + " on conflict (consumer_id) do nothing",
                consumerId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return jdbc.queryForObject("select last_event_seq from " + CONSUMER_TABLE
                + " where consumer_id = ?", Long.class, consumerId);
    }

    private SkillSearchRefreshCleanupResult purgeConsumedBeforeInternal(Instant cutoff, int limit, boolean lock) {
        return purgeConsumedBeforeInternal(cutoff, null, limit, lock);
    }

    private SkillSearchRefreshCleanupResult purgeConsumedBeforeInternal(Instant cutoff, Instant activeSince,
                                                                         int limit, boolean lock) {
        if (lock) acquireMaintenanceLock();
        // A stale consumer may be excluded only after proving it has already reached
        // every event eligible for deletion. Otherwise its cursor remains protective.
        Long watermark = activeSince == null
                ? jdbc.queryForObject("select min(last_event_seq) from " + CONSUMER_TABLE
                        + " where status = 'ACTIVE'", Long.class, new Object[0])
                : jdbc.queryForObject("select min(last_event_seq) from " + CONSUMER_TABLE
                        + " where status = 'ACTIVE' and (last_seen_at >= ? or last_event_seq < coalesce("
                        + "(select max(event_seq) from " + TABLE + " where created_at < ?), -1))",
                Long.class, Timestamp.from(activeSince), Timestamp.from(cutoff));
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

    @Override
    public SkillSearchRefreshConsumerState registerConsumer(String consumerId, Instant now) {
        String bounded = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        Instant checkedNow = requireInstant(now);
        try {
            return executeMaintenance(() -> {
                jdbc.update("insert into " + CONSUMER_TABLE
                                + " (consumer_id, last_event_seq, updated_at, status, last_seen_at)"
                                + " values (?, 0, ?, 'ACTIVE', ?) on conflict (consumer_id) do nothing",
                        bounded, Timestamp.from(checkedNow), Timestamp.from(checkedNow));
                jdbc.update("update " + CONSUMER_TABLE
                                + " set last_seen_at = ?, updated_at = ?"
                                + " where consumer_id = ? and status = 'ACTIVE'",
                        Timestamp.from(checkedNow), Timestamp.from(checkedNow), bounded);
                return readConsumer(bounded);
            });
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public SkillSearchRefreshConsumerState heartbeat(String consumerId, Instant now) {
        String bounded = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        Instant checkedNow = requireInstant(now);
        try {
            return executeMaintenance(() -> {
                jdbc.update("update " + CONSUMER_TABLE
                                + " set last_seen_at = ?, updated_at = ?"
                                + " where consumer_id = ? and status = 'ACTIVE'",
                        Timestamp.from(checkedNow), Timestamp.from(checkedNow), bounded);
                SkillSearchRefreshConsumerState state = readConsumer(bounded);
                if (state == null) throw new SkillSearchIndexControlException("SEARCH_INDEX_CONSUMER_NOT_FOUND");
                return state;
            });
        } catch (SkillSearchIndexControlException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public SkillSearchRefreshConsumerState activateConsumer(String consumerId, Instant now) {
        String bounded = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        Instant checkedNow = requireInstant(now);
        try {
            return executeMaintenance(() -> {
                jdbc.update("insert into " + CONSUMER_TABLE
                                + " (consumer_id, last_event_seq, updated_at, status, last_seen_at)"
                                + " values (?, 0, ?, 'ACTIVE', ?)"
                                + " on conflict (consumer_id) do update set status = 'ACTIVE',"
                                + " last_seen_at = excluded.last_seen_at, retired_at = null,"
                                + " updated_at = excluded.updated_at",
                        bounded, Timestamp.from(checkedNow), Timestamp.from(checkedNow));
                return readConsumer(bounded);
            });
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public SkillSearchRefreshConsumerState retireConsumer(String consumerId, Instant now) {
        String bounded = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        Instant checkedNow = requireInstant(now);
        try {
            return executeMaintenance(() -> {
                int updated = jdbc.update("update " + CONSUMER_TABLE
                                + " set status = 'RETIRED', retired_at = ?, updated_at = ?"
                                + " where consumer_id = ?",
                        Timestamp.from(checkedNow), Timestamp.from(checkedNow), bounded);
                if (updated == 0) throw new SkillSearchIndexControlException("SEARCH_INDEX_CONSUMER_NOT_FOUND");
                return readConsumer(bounded);
            });
        } catch (SkillSearchIndexControlException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public List<SkillSearchRefreshConsumerState> listConsumers() {
        try {
            return new ArrayList<>(jdbc.query("select consumer_id, last_event_seq, status, last_seen_at, retired_at"
                            + " from " + CONSUMER_TABLE + " order by consumer_id",
                    this::mapConsumer));
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public SkillSearchRefreshCleanupResult purgeConsumedBefore(Instant cutoff, Instant activeSince, int limit) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        if (activeSince == null) throw new IllegalArgumentException("activeSince is required");
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        try {
            if (transactions == null) return purgeConsumedBeforeInternal(cutoff, activeSince, limit, false);
            SkillSearchRefreshCleanupResult result = transactions.execute(status ->
                    purgeConsumedBeforeInternal(cutoff, activeSince, limit, true));
            if (result == null) throw new IllegalStateException("cleanup transaction returned no result");
            return result;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private SkillSearchRefreshConsumerState readConsumer(String consumerId) {
        List<SkillSearchRefreshConsumerState> states = jdbc.query("select consumer_id, last_event_seq, status,"
                        + " last_seen_at, retired_at from " + CONSUMER_TABLE + " where consumer_id = ?",
                this::mapConsumer, consumerId);
        return states.isEmpty() ? null : states.get(0);
    }

    private SkillSearchRefreshConsumerState mapConsumer(ResultSet resultSet, int ignored) throws SQLException {
        Timestamp lastSeenAt = resultSet.getTimestamp("last_seen_at");
        Timestamp retiredAt = resultSet.getTimestamp("retired_at");
        return new SkillSearchRefreshConsumerState(resultSet.getString("consumer_id"),
                resultSet.getLong("last_event_seq"),
                SkillSearchRefreshConsumerStatus.valueOf(resultSet.getString("status")),
                lastSeenAt == null ? Instant.EPOCH : lastSeenAt.toInstant(),
                retiredAt == null ? null : retiredAt.toInstant());
    }

    private <T> T executeMaintenance(Supplier<T> operation) {
        if (transactions == null) return operation.get();
        T result = transactions.execute(status -> {
            acquireMaintenanceLock();
            return operation.get();
        });
        if (result == null) throw new IllegalStateException("consumer lifecycle transaction returned no result");
        return result;
    }

    private RuntimeException persistence(RuntimeException exception) {
        if (exception instanceof SkillSearchIndexControlException) return exception;
        if (exception instanceof SkillSearchIndexPersistenceException) return exception;
        return new SkillSearchIndexPersistenceException(exception);
    }

    private static Instant requireInstant(Instant value) {
        if (value == null) throw new IllegalArgumentException("timestamp is required");
        return value;
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
