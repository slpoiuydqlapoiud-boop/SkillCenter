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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL JSONB implementation of post-release observation evidence. */
@Component
@Conditional(OptimizationExperimentBackendCondition.Postgresql.class)
public class JdbcOptimizationExperimentObservationStore implements OptimizationExperimentObservationRepository {
    private static final String TABLE = "skill_optimization_experiment_observations";
    private static final String SELECT = "select payload::text as payload from " + TABLE;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcOptimizationExperimentObservationStore(JdbcTemplate jdbc, ObjectMapper mapper,
                                                      PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<OptimizationExperimentObservation> findAll(String experimentId) {
        try {
            if (experimentId == null || experimentId.isBlank()) {
                return jdbc.query(SELECT + " order by captured_at desc, observation_id", this::map);
            }
            return jdbc.query(SELECT + " where experiment_id = ? order by captured_at desc, observation_id",
                    this::map, experimentId.trim());
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public Optional<OptimizationExperimentObservation> find(String observationId) {
        if (observationId == null || observationId.isBlank()) return Optional.empty();
        try {
            List<OptimizationExperimentObservation> values = jdbc.query(SELECT + " where observation_id = ?",
                    this::map, observationId.trim());
            if (values.size() > 1) throw new IllegalArgumentException("observation query returned duplicate rows");
            return values.stream().findFirst();
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public long countBefore(Instant cutoff) {
        require(cutoff, "cutoff");
        try {
            Long count = jdbc.queryForObject("select count(*) from " + TABLE + " where captured_at < ?",
                    Long.class, Timestamp.from(cutoff));
            return count == null ? 0 : count;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public long deleteBefore(Instant cutoff) {
        require(cutoff, "cutoff");
        try {
            return transactions.execute(status -> {
                try {
                    return (long) jdbc.update("delete from " + TABLE + " where captured_at < ?",
                            Timestamp.from(cutoff));
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw failure(exception);
                }
            });
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public OptimizationExperimentObservation create(OptimizationExperimentObservation value) {
        require(value, "observation");
        try {
            return transactions.execute(status -> {
                try {
                    jdbc.update("insert into " + TABLE
                                    + " (observation_id, experiment_id, captured_at, payload) values (?, ?, ?, ?::jsonb)",
                            value.observationId(), value.experimentId(), Timestamp.from(value.capturedAt()), payload(value));
                    return value;
                } catch (DuplicateKeyException exception) {
                    status.setRollbackOnly();
                    throw new OptimizationExperimentConflictException("observationId already exists");
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

    private String payload(OptimizationExperimentObservation value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("observation cannot be serialized", exception);
        }
    }

    private OptimizationExperimentObservation map(java.sql.ResultSet resultSet, int ignored) throws java.sql.SQLException {
        try {
            OptimizationExperimentObservation value = mapper.readValue(resultSet.getString("payload"),
                    OptimizationExperimentObservation.class);
            if (value == null) throw new IllegalArgumentException("observation is invalid");
            return value;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("observation is invalid", exception);
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
