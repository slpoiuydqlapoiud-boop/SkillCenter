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
