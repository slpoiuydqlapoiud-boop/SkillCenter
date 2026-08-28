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

/** PostgreSQL JSONB implementation of the optimization work-item repository. */
@Component
@Conditional(OptimizationWorkItemBackendCondition.Postgresql.class)
public class JdbcOptimizationWorkItemStore implements OptimizationWorkItemRepository {
    private static final String TABLE = "skill_optimization_work_items";
    private static final String SELECT = "select payload::text as payload from " + TABLE;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcOptimizationWorkItemStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                         PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbcTemplate, "jdbcTemplate");
        this.mapper = require(objectMapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<OptimizationWorkItem> findAll(String skillId, String status, String ownerId, String sourceVersion) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where 1=1");
        List<Object> arguments = new ArrayList<>();
        appendFilter(sql, arguments, "skill_id", skillId);
        appendFilter(sql, arguments, "status", status);
        appendFilter(sql, arguments, "payload->>'ownerId'", ownerId);
        appendFilter(sql, arguments, "source_version", sourceVersion);
        sql.append(" order by updated_at desc, work_item_id");
        try {
            return jdbc.query(sql.toString(), this::map, arguments.toArray());
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public Optional<OptimizationWorkItem> find(String workItemId) {
        if (workItemId == null || workItemId.isBlank()) return Optional.empty();
        try {
            List<OptimizationWorkItem> values = jdbc.query(SELECT + " where work_item_id = ?",
                    this::map, workItemId.trim());
            if (values.size() > 1) throw new IllegalArgumentException("work item query returned duplicate rows");
            return values.stream().findFirst();
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public OptimizationWorkItem create(OptimizationWorkItem value) {
        require(value, "work item");
        try {
            return transactions.execute(status -> {
                try {
                    jdbc.update("insert into " + TABLE
                                    + " (work_item_id, skill_id, source_version, suggestion_id, status, created_at, updated_at, payload)"
                                    + " values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                            parameters(value));
                    return value;
                } catch (DuplicateKeyException exception) {
                    status.setRollbackOnly();
                    throw conflictAfterConstraint(value, exception);
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
    public OptimizationWorkItem replace(OptimizationWorkItem value) {
        require(value, "work item");
        try {
            return transactions.execute(status -> {
                if (findLocked(value.workItemId()).isEmpty()) {
                    throw new IllegalArgumentException("workItemId does not exist");
                }
                try {
                    int updated = jdbc.update("update " + TABLE
                                    + " set skill_id = ?, source_version = ?, suggestion_id = ?, status = ?, "
                                    + "created_at = ?, updated_at = ?, payload = ?::jsonb where work_item_id = ?",
                            updateParameters(value));
                    if (updated != 1) throw new IllegalStateException("work item update was not applied");
                    return value;
                } catch (DuplicateKeyException exception) {
                    status.setRollbackOnly();
                    throw conflictAfterConstraint(value, exception);
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

    private Optional<OptimizationWorkItem> findLocked(String workItemId) {
        List<OptimizationWorkItem> values = jdbc.query(SELECT + " where work_item_id = ? for update",
                this::map, workItemId);
        if (values.size() > 1) throw new IllegalArgumentException("work item query returned duplicate rows");
        return values.stream().findFirst();
    }

    private void appendFilter(StringBuilder sql, List<Object> arguments, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" and ").append(column).append(" = ?");
            arguments.add("status".equals(column)
                    ? OptimizationWorkItemStatus.normalize(value)
                    : value.trim());
        }
    }

    private Object[] parameters(OptimizationWorkItem value) {
        return new Object[]{value.workItemId(), value.skillId(), value.sourceVersion(), value.suggestionId(),
                value.status(), Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()), payload(value)};
    }

    private Object[] updateParameters(OptimizationWorkItem value) {
        return new Object[]{value.skillId(), value.sourceVersion(), value.suggestionId(), value.status(),
                Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()), payload(value), value.workItemId()};
    }

    private String payload(OptimizationWorkItem value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("work item cannot be serialized", exception);
        }
    }

    private OptimizationWorkItem map(java.sql.ResultSet resultSet, int ignored) throws java.sql.SQLException {
        try {
            OptimizationWorkItem value = mapper.readValue(resultSet.getString("payload"), OptimizationWorkItem.class);
            if (value == null) throw new IllegalArgumentException("work item is invalid");
            return value;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("work item is invalid", exception);
        }
    }

    private IllegalArgumentException conflictAfterConstraint(OptimizationWorkItem value, Throwable cause) {
        String message = OptimizationWorkItemStatus.isTerminal(value.status())
                ? "workItemId already exists" : "active work item already exists";
        return new IllegalArgumentException(message, cause);
    }

    private OptimizationWorkItemStore.OptimizationWorkItemPersistenceException failure(Throwable cause) {
        if (cause instanceof OptimizationWorkItemStore.OptimizationWorkItemPersistenceException persistence) {
            return persistence;
        }
        return new OptimizationWorkItemStore.OptimizationWorkItemPersistenceException(cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
