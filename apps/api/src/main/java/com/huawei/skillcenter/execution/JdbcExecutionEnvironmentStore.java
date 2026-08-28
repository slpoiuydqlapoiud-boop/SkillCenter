package com.huawei.skillcenter.execution;

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

/** PostgreSQL implementation of the execution-environment asset repository. */
@Component
@Conditional(ExecutionEnvironmentBackendCondition.Postgresql.class)
public class JdbcExecutionEnvironmentStore implements ExecutionEnvironmentRepository {
    private static final String TABLE = "skill_execution_environment";
    private static final String SELECT = "select payload::text as payload from " + TABLE;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcExecutionEnvironmentStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                         PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbcTemplate, "jdbcTemplate");
        this.mapper = require(objectMapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<ExecutionEnvironment> findAll(ExecutionEnvironmentKind kind, ExecutionEnvironmentStatus status) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where 1=1");
        List<Object> arguments = new ArrayList<>();
        if (kind != null) {
            sql.append(" and kind = ?");
            arguments.add(kind.name());
        }
        if (status != null) {
            sql.append(" and status = ?");
            arguments.add(status.name());
        }
        sql.append(" order by kind, environment_id");
        try {
            return jdbc.query(sql.toString(), this::map, arguments.toArray());
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public Optional<ExecutionEnvironment> find(ExecutionEnvironmentKind kind, String environmentId) {
        if (kind == null || environmentId == null || environmentId.isBlank()) return Optional.empty();
        try {
            List<ExecutionEnvironment> values = jdbc.query(
                    SELECT + " where kind = ? and environment_id = ?", this::map,
                    kind.name(), environmentId.trim());
            if (values.size() > 1) throw new IllegalArgumentException("execution environment query returned duplicate rows");
            return values.stream().findFirst();
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public ExecutionEnvironment create(ExecutionEnvironment value) {
        require(value, "execution environment");
        try {
            return transactions.execute(status -> {
                try {
                    jdbc.update("insert into " + TABLE
                                    + " (environment_id, kind, version, status, capabilities, adapter_provider_id, "
                                    + "config_reference, created_by, created_at, updated_by, updated_at, revision, payload)"
                                    + " values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                            parameters(value));
                    return value;
                } catch (DuplicateKeyException exception) {
                    status.setRollbackOnly();
                    throw new ExecutionEnvironmentConflictException("execution environment already exists");
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw failure(exception);
                }
            });
        } catch (ExecutionEnvironmentConflictException | ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public ExecutionEnvironment replace(ExecutionEnvironment value, int expectedRevision) {
        require(value, "execution environment");
        if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision must be at least 1");
        if (value.revision() != expectedRevision + 1) {
            throw new ExecutionEnvironmentRevisionConflictException("execution environment revision conflict");
        }
        try {
            return transactions.execute(status -> {
                Optional<ExecutionEnvironment> current = findLocked(value.kind(), value.environmentId());
                if (current.isEmpty()) throw new IllegalArgumentException("execution environment does not exist");
                if (current.get().revision() != expectedRevision) {
                    throw new ExecutionEnvironmentRevisionConflictException("execution environment revision conflict");
                }
                try {
                    int updated = jdbc.update("update " + TABLE
                                    + " set version = ?, status = ?, capabilities = ?::jsonb, adapter_provider_id = ?, "
                                    + "config_reference = ?, created_by = ?, created_at = ?, updated_by = ?, updated_at = ?, "
                                    + "revision = ?, payload = ?::jsonb where kind = ? and environment_id = ? and revision = ?",
                            updateParameters(value, expectedRevision));
                    if (updated != 1) throw new ExecutionEnvironmentRevisionConflictException(
                            "execution environment revision conflict");
                    return value;
                } catch (DuplicateKeyException exception) {
                    status.setRollbackOnly();
                    throw new ExecutionEnvironmentConflictException("execution environment already exists");
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw failure(exception);
                }
            });
        } catch (IllegalArgumentException | ExecutionEnvironmentRevisionConflictException
                 | ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private Optional<ExecutionEnvironment> findLocked(ExecutionEnvironmentKind kind, String environmentId) {
        List<ExecutionEnvironment> values = jdbc.query(
                SELECT + " where kind = ? and environment_id = ? for update", this::map,
                kind.name(), environmentId);
        if (values.size() > 1) throw new IllegalArgumentException("execution environment query returned duplicate rows");
        return values.stream().findFirst();
    }

    private Object[] parameters(ExecutionEnvironment value) {
        return new Object[]{value.environmentId(), value.kind().name(), value.version(), value.status().name(),
                mapperValue(value.capabilities()), value.adapterProviderId(), value.configReference(), value.createdBy(),
                Timestamp.from(value.createdAt()), value.updatedBy(), Timestamp.from(value.updatedAt()), value.revision(),
                mapperValue(value)};
    }

    private Object[] updateParameters(ExecutionEnvironment value, int expectedRevision) {
        return new Object[]{value.version(), value.status().name(), mapperValue(value.capabilities()),
                value.adapterProviderId(), value.configReference(), value.createdBy(), Timestamp.from(value.createdAt()),
                value.updatedBy(), Timestamp.from(value.updatedAt()), value.revision(), mapperValue(value),
                value.kind().name(), value.environmentId(), expectedRevision};
    }

    private String mapperValue(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("execution environment cannot be serialized", exception);
        }
    }

    private ExecutionEnvironment map(java.sql.ResultSet resultSet, int ignored) throws java.sql.SQLException {
        try {
            ExecutionEnvironment value = mapper.readValue(resultSet.getString("payload"), ExecutionEnvironment.class);
            if (value == null) throw new IllegalArgumentException("execution environment is invalid");
            return value;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("execution environment is invalid", exception);
        }
    }

    private ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException failure(Throwable cause) {
        if (cause instanceof ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException persistence) {
            return persistence;
        }
        return new ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException(cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
