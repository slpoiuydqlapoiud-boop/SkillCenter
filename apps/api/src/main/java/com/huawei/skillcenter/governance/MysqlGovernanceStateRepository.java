package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.search.SkillSearchRefreshEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** MySQL JSON persistence for the department governance aggregate. */
@Component
@Conditional(GovernanceBackendCondition.Mysql.class)
public class MysqlGovernanceStateRepository implements GovernanceStateRepository {
    private static final String STATE_KEY = "governance-state";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public MysqlGovernanceStateRepository(JdbcTemplate jdbc,
                                          ObjectMapper objectMapper,
                                          DataSourceTransactionManager transactionManager) {
        if (jdbc == null || objectMapper == null || transactionManager == null) {
            throw new IllegalArgumentException("jdbc, objectMapper and transactionManager are required");
        }
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<GovernanceState> load() {
        try {
            return transactions.execute(status -> read(false));
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public GovernanceState loadOrSeed(Supplier<GovernanceSnapshot> seed) {
        if (seed == null) throw new IllegalArgumentException("seed must not be null");
        try {
            return transactions.execute(status -> {
                Optional<GovernanceState> existing = read(true);
                if (existing.isPresent()) return existing.get();
                GovernanceSnapshot snapshot = seed.get();
                if (snapshot == null) throw new IllegalArgumentException("seed snapshot must not be null");
                jdbc.update("insert into department_platform_state (state_key, revision, state) values (?, ?, ?)",
                        STATE_KEY, 0L, json(snapshot));
                return new GovernanceState(0L, snapshot);
            });
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public GovernanceState replace(long expectedRevision, GovernanceSnapshot snapshot) {
        return replace(expectedRevision, snapshot, List.of());
    }

    @Override
    public GovernanceState replace(long expectedRevision, GovernanceSnapshot snapshot,
                                   List<SkillSearchRefreshEvent> refreshEvents) {
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        if (refreshEvents != null && !refreshEvents.isEmpty()) {
            throw new IllegalArgumentException("department MySQL mode does not enable the refresh event bus");
        }
        try {
            return transactions.execute(status -> {
                Optional<GovernanceState> current = read(true);
                if (current.isEmpty()) {
                    throw new GovernanceStore.GovernancePersistenceException(
                            new IllegalStateException("governance state row is missing"));
                }
                long nextRevision = Math.addExact(expectedRevision, 1L);
                int updated = jdbc.update("update department_platform_state set state = ?, revision = ?, "
                                + "updated_at = current_timestamp where state_key = ? and revision = ?",
                        json(snapshot), nextRevision, STATE_KEY, expectedRevision);
                if (updated != 1) throw new GovernanceStateConflictException("governance state revision conflict");
                return new GovernanceState(nextRevision, snapshot);
            });
        } catch (GovernanceStateConflictException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private Optional<GovernanceState> read(boolean lock) {
        String sql = "select revision, state from department_platform_state where state_key = ?"
                + (lock ? " for update" : "");
        List<GovernanceState> rows = jdbc.query(sql, mapper(), STATE_KEY);
        if (rows.size() > 1) throw new IllegalStateException("governance state returned duplicate rows");
        return rows.stream().findFirst();
    }

    private RowMapper<GovernanceState> mapper() {
        return (resultSet, ignored) -> {
            try {
                return new GovernanceState(resultSet.getLong("revision"),
                        objectMapper.readValue(resultSet.getString("state"), GovernanceSnapshot.class));
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                throw new IllegalArgumentException("governance state is invalid", exception);
            }
        };
    }

    private String json(GovernanceSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("governance state cannot be serialized", exception);
        }
    }

    private GovernanceStore.GovernancePersistenceException failure(Throwable cause) {
        if (cause instanceof GovernanceStore.GovernancePersistenceException persistence) return persistence;
        return new GovernanceStore.GovernancePersistenceException(cause);
    }
}
