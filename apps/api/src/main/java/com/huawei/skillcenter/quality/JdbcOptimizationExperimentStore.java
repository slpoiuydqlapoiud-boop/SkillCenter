package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL JSONB implementation of the optimization experiment repository. */
@Component
@Conditional(OptimizationExperimentBackendCondition.Postgresql.class)
public class JdbcOptimizationExperimentStore implements OptimizationExperimentRepository {
    private static final String TABLE = "skill_optimization_experiments";
    private static final String SELECT = "select payload::text as payload from " + TABLE;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcOptimizationExperimentStore(JdbcTemplate jdbc, ObjectMapper mapper,
                                           PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<OptimizationExperiment> findAll(String skillId, String workItemId, String status) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where 1=1");
        List<Object> arguments = new ArrayList<>();
        appendFilter(sql, arguments, "skill_id", skillId);
        appendFilter(sql, arguments, "work_item_id", workItemId);
        appendFilter(sql, arguments, "status", status == null || status.isBlank()
                ? status : OptimizationExperimentStatus.normalize(status));
        sql.append(" order by updated_at desc, experiment_id");
        try {
            return jdbc.query(sql.toString(), this::map, arguments.toArray());
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public Optional<OptimizationExperiment> find(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) return Optional.empty();
        try {
            List<OptimizationExperiment> values = jdbc.query(SELECT + " where experiment_id = ?",
                    this::map, experimentId.trim());
            if (values.size() > 1) throw new IllegalArgumentException("experiment query returned duplicate rows");
            return values.stream().findFirst();
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public Optional<OptimizationExperiment> findActiveByWorkItemId(String workItemId) {
        if (workItemId == null || workItemId.isBlank()) return Optional.empty();
        try {
            List<OptimizationExperiment> values = jdbc.query(SELECT
                    + " where work_item_id = ? and status in ('QUEUED', 'RUNNING')"
                    + " order by updated_at desc, experiment_id limit 1", this::map, workItemId.trim());
            return values.stream().findFirst();
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public OptimizationExperiment create(OptimizationExperiment value) {
        require(value, "experiment");
        try {
            return transactions.execute(status -> {
                try {
                    jdbc.update("insert into " + TABLE
                                    + " (experiment_id, work_item_id, skill_id, status, updated_at, payload)"
                                    + " values (?, ?, ?, ?, ?, ?::jsonb)",
                            value.experimentId(), value.workItemId(), value.skillId(), value.status(),
                            Timestamp.from(value.updatedAt()), payload(value));
                    return value;
                } catch (DuplicateKeyException exception) {
                    status.setRollbackOnly();
                    throw new OptimizationExperimentConflictException(
                            OptimizationExperimentStatus.isTerminal(value.status())
                                    ? "experimentId already exists" : "active experiment already exists for work item");
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw failure(exception);
                }
            });
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public OptimizationExperiment replace(OptimizationExperiment value) {
        require(value, "experiment");
        try {
            return transactions.execute(status -> {
                Optional<OptimizationExperiment> existing = findLocked(value.experimentId());
                if (existing.isEmpty()) throw new OptimizationExperimentNotFoundException(value.experimentId());
                if (existing.get().decision() != null && !existing.get().decision().equals(value.decision())) {
                    throw new OptimizationExperimentConflictException("decision snapshot is immutable");
                }
                try {
                    int updated = jdbc.update("update " + TABLE
                                    + " set work_item_id = ?, skill_id = ?, status = ?, updated_at = ?, payload = ?::jsonb"
                                    + " where experiment_id = ?",
                            value.workItemId(), value.skillId(), value.status(), Timestamp.from(value.updatedAt()),
                            payload(value), value.experimentId());
                    if (updated != 1) throw new IllegalStateException("experiment update was not applied");
                    return value;
                } catch (DuplicateKeyException exception) {
                    status.setRollbackOnly();
                    throw new OptimizationExperimentConflictException("active experiment already exists for work item");
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw failure(exception);
                }
            });
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private Optional<OptimizationExperiment> findLocked(String experimentId) {
        List<OptimizationExperiment> values = jdbc.query(SELECT + " where experiment_id = ? for update",
                this::map, experimentId);
        if (values.size() > 1) throw new IllegalArgumentException("experiment query returned duplicate rows");
        return values.stream().findFirst();
    }

    private void appendFilter(StringBuilder sql, List<Object> arguments, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" and ").append(column).append(" = ?");
            arguments.add(value.trim());
        }
    }

    private String payload(OptimizationExperiment value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("experiment cannot be serialized", exception);
        }
    }

    private OptimizationExperiment map(java.sql.ResultSet resultSet, int ignored) throws java.sql.SQLException {
        try {
            OptimizationExperiment value = mapper.readValue(resultSet.getString("payload"), OptimizationExperiment.class);
            if (value == null) throw new IllegalArgumentException("experiment is invalid");
            return value;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("experiment is invalid", exception);
        }
    }

    private OptimizationExperimentPersistenceException failure(Throwable cause) {
        if (cause instanceof OptimizationExperimentPersistenceException persistence) return persistence;
        return new OptimizationExperimentPersistenceException(cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
