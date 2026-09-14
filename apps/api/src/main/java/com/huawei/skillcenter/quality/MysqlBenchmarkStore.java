package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** MySQL JSON document persistence for immutable department Benchmark evidence. */
@Component
@Conditional(BenchmarkBackendCondition.Mysql.class)
public class MysqlBenchmarkStore implements BenchmarkRepository {
    private static final String KEY = "benchmarks";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload from department_json_documents where document_key = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public MysqlBenchmarkStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<BenchmarkResult> findAll(String skillId) {
        return load().stream()
                .filter(result -> skillId == null || skillId.isBlank() || result.skillId().equals(skillId.trim()))
                .sorted(Comparator.comparing(BenchmarkResult::createdAt).reversed())
                .toList();
    }

    @Override
    public BenchmarkResult findByExperimentId(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) return null;
        return load().stream().filter(result -> experimentId.trim().equals(result.experimentId())).findFirst().orElse(null);
    }

    @Override
    public BenchmarkResult add(BenchmarkResult result) {
        BenchmarkStore.validate(result);
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<BenchmarkResult> values = current == null ? new ArrayList<>() : parse(current);
                if (values.stream().anyMatch(existing -> existing.benchmarkId().equals(result.benchmarkId()))) {
                    throw new IllegalArgumentException("benchmarkId already exists: " + result.benchmarkId());
                }
                values.add(result);
                write(values, current);
                return result;
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
        Set<String> protectedIds = protectedBenchmarkIds == null ? Set.of() : Set.copyOf(protectedBenchmarkIds);
        return load().stream().filter(result -> result.createdAt() != null && result.createdAt().isBefore(cutoff)
                && !protectedIds.contains(result.benchmarkId())).count();
    }

    @Override
    public int deleteBefore(Instant cutoff, Set<String> protectedBenchmarkIds) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        Set<String> protectedIds = protectedBenchmarkIds == null ? Set.of() : Set.copyOf(protectedBenchmarkIds);
        try {
            return transactions.execute(status -> {
                Row current = locked();
                if (current == null) return 0;
                List<BenchmarkResult> before = parse(current);
                List<BenchmarkResult> retained = before.stream()
                        .filter(result -> result.createdAt() == null || !result.createdAt().isBefore(cutoff)
                                || protectedIds.contains(result.benchmarkId())).toList();
                int removed = before.size() - retained.size();
                if (removed > 0) write(retained, current);
                return removed;
            });
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private List<BenchmarkResult> load() {
        try {
            List<Row> rows = jdbc.query(SELECT, this::row, KEY);
            if (rows.isEmpty()) return List.of();
            if (rows.size() != 1) throw new IllegalArgumentException("benchmark aggregate is invalid");
            return parse(rows.getFirst());
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private Row locked() {
        List<Row> rows = jdbc.query(SELECT + " for update", this::row, KEY);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException("benchmark aggregate is invalid");
        return rows.getFirst();
    }

    private void write(List<BenchmarkResult> values, Row current) {
        long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
        String payload = serialize(values);
        if (current == null) {
            jdbc.update("insert into department_json_documents (document_key, document_schema_version, revision, payload) values (?, ?, ?, ?)",
                    KEY, SCHEMA_VERSION, revision, payload);
            return;
        }
        int updated = jdbc.update("update department_json_documents set document_schema_version = ?, revision = ?, payload = ?, updated_at = current_timestamp where document_key = ? and revision = ?",
                SCHEMA_VERSION, revision, payload, KEY, current.revision());
        if (updated != 1) throw new IllegalStateException("benchmark revision conflict");
    }

    private Row row(ResultSet resultSet, int ignored) throws SQLException {
        return new Row(resultSet.getInt("document_schema_version"), resultSet.getLong("revision"), resultSet.getString("payload"));
    }

    private List<BenchmarkResult> parse(Row row) {
        if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) {
            throw new IllegalArgumentException("benchmark aggregate is invalid");
        }
        try {
            List<BenchmarkResult> values = mapper.readValue(row.payload(), new TypeReference<>() { });
            List<BenchmarkResult> normalized = List.copyOf(values == null ? List.of() : values);
            Set<String> ids = new HashSet<>();
            Set<String> experiments = new HashSet<>();
            normalized.forEach(value -> {
                BenchmarkStore.validate(value);
                if (!ids.add(value.benchmarkId())) throw new IllegalArgumentException("duplicate benchmarkId: " + value.benchmarkId());
                if (!value.experimentId().isBlank() && !experiments.add(value.experimentId())) {
                    throw new IllegalArgumentException("duplicate benchmark experimentId: " + value.experimentId());
                }
            });
            return normalized;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("benchmark aggregate is invalid", exception);
        }
    }

    private String serialize(List<BenchmarkResult> values) {
        try { return mapper.writeValueAsString(values); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("benchmark aggregate cannot be serialized", exception); }
    }

    private BenchmarkStore.BenchmarkPersistenceException failure(Throwable cause) {
        if (cause instanceof BenchmarkStore.BenchmarkPersistenceException persistence) return persistence;
        return new BenchmarkStore.BenchmarkPersistenceException(cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record Row(int schemaVersion, long revision, String payload) { }
}
