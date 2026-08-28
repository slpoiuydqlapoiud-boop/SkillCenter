package com.huawei.skillcenter.search;

import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/** PostgreSQL refresh journal with deterministic event-key idempotency. */
@Conditional(SkillSearchBackendCondition.Postgresql.class)
public final class JdbcSkillSearchRefreshEventStore implements SkillSearchRefreshEventStore {
    private static final String TABLE = "skill_search_refresh_events";
    private static final String CONSUMER_TABLE = "skill_search_refresh_event_consumers";
    private final JdbcTemplate jdbc;

    public JdbcSkillSearchRefreshEventStore(JdbcTemplate jdbc) {
        this.jdbc = require(jdbc, "jdbcTemplate");
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
                            + " values (?, ?, ?, ?, ?)",
                    event.eventKey(), event.skillId(), event.sourceRevision(), event.reasonCode(),
                    Timestamp.from(java.time.Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // At-least-once publication is safe: the deterministic event key already exists.
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
            jdbc.update("insert into " + CONSUMER_TABLE
                    + " (consumer_id, last_event_seq, updated_at) values (?, 0, ?)"
                    + " on conflict (consumer_id) do nothing",
                    boundedConsumerId, Timestamp.from(java.time.Instant.now()));
            Long sequence = jdbc.queryForObject("select last_event_seq from " + CONSUMER_TABLE
                    + " where consumer_id = ?", Long.class, boundedConsumerId);
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
