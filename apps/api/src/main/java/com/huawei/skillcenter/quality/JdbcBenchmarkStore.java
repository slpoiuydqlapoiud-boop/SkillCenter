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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** PostgreSQL JSONB persistence for immutable Benchmark evidence. */
@Component
@Conditional(BenchmarkBackendCondition.Postgresql.class)
public class JdbcBenchmarkStore implements BenchmarkRepository {
    private static final String TABLE = "skill_benchmarks";
    private static final String SELECT = "select payload::text as payload from " + TABLE;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcBenchmarkStore(JdbcTemplate jdbc, ObjectMapper mapper,
                              PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<BenchmarkResult> findAll(String skillId) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> arguments = new ArrayList<>();
        if (skillId != null && !skillId.isBlank()) {
            sql.append(" where skill_id = ?");
            arguments.add(skillId.trim());
        }
        sql.append(" order by created_at desc, benchmark_id");
        try {
            return jdbc.query(sql.toString(), this::map, arguments.toArray());
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public BenchmarkResult findByExperimentId(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) return null;
        try {
            List<BenchmarkResult> values = jdbc.query(SELECT + " where experiment_id = ? limit 1",
                    this::map, experimentId.trim());
            return values.isEmpty() ? null : values.getFirst();
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public BenchmarkResult add(BenchmarkResult result) {
        BenchmarkStore.validate(result);
        try {
            return transactions.execute(status -> {
                try {
                    jdbc.update("insert into " + TABLE
                                    + " (benchmark_id, skill_id, experiment_id, created_at, payload)"
                                    + " values (?, ?, ?, ?, ?::jsonb)",
                            result.benchmarkId(), result.skillId(), nullable(result.experimentId()),
                            Timestamp.from(result.createdAt()), payload(result));
                    return result;
                } catch (DuplicateKeyException duplicate) {
                    if (result.experimentId() != null && !result.experimentId().isBlank()) {
                        BenchmarkResult existing = findByExperimentId(result.experimentId());
                        if (existing != null) return existing;
                    }
                    status.setRollbackOnly();
                    throw new IllegalArgumentException("benchmarkId already exists");
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw failure(exception);
                }
            });
        } catch (BenchmarkStore.BenchmarkPersistenceException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public long countBefore(Instant cutoff, Set<String> protectedBenchmarkIds) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        try {
            Query query = protectedQuery("select count(*) from " + TABLE + " where created_at < ?",
                    cutoff, protectedBenchmarkIds);
            Long count = jdbc.queryForObject(query.sql(), Long.class, query.arguments().toArray());
            return count == null ? 0 : count;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public int deleteBefore(Instant cutoff, Set<String> protectedBenchmarkIds) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        try {
            Query query = protectedQuery("delete from " + TABLE + " where created_at < ?",
                    cutoff, protectedBenchmarkIds);
            Integer deleted = transactions.execute(status -> {
                try {
                    return jdbc.update(query.sql(), query.arguments().toArray());
                } catch (RuntimeException exception) {
                    status.setRollbackOnly();
                    throw failure(exception);
                }
            });
            return deleted == null ? 0 : deleted;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private Query protectedQuery(String base, Instant cutoff, Set<String> protectedIds) {
        Set<String> safe = protectedIds == null ? Set.of() : Set.copyOf(protectedIds);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(cutoff));
        if (safe.isEmpty()) return new Query(base, arguments);
        String placeholders = String.join(",", safe.stream().map(ignored -> "?").toList());
        arguments.addAll(safe);
        return new Query(base + " and benchmark_id not in (" + placeholders + ")", arguments);
    }

    private BenchmarkResult map(java.sql.ResultSet resultSet, int ignored) throws java.sql.SQLException {
        try {
            BenchmarkResult result = mapper.readValue(resultSet.getString("payload"), BenchmarkResult.class);
            BenchmarkStore.validate(result);
            return result;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("benchmark result is invalid", exception);
        }
    }

    private String payload(BenchmarkResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("benchmark result cannot be serialized", exception);
        }
    }

    private BenchmarkStore.BenchmarkPersistenceException failure(Throwable cause) {
        if (cause instanceof BenchmarkStore.BenchmarkPersistenceException persistence) return persistence;
        return new BenchmarkStore.BenchmarkPersistenceException(cause);
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record Query(String sql, List<Object> arguments) { }
}
