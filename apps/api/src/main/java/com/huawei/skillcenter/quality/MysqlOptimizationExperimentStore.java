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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** MySQL JSON document persistence for department optimization experiments. */
@Component
@Conditional(OptimizationExperimentBackendCondition.Mysql.class)
public class MysqlOptimizationExperimentStore implements OptimizationExperimentRepository {
    private static final String KEY = "optimization-experiments";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload from department_json_documents where document_key = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public MysqlOptimizationExperimentStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<OptimizationExperiment> findAll(String skillId, String workItemId, String status) {
        return load().stream()
                .filter(value -> skillId == null || skillId.isBlank() || skillId.equals(value.skillId()))
                .filter(value -> workItemId == null || workItemId.isBlank() || workItemId.equals(value.workItemId()))
                .filter(value -> status == null || status.isBlank() || OptimizationExperimentStatus.normalize(status).equals(value.status()))
                .sorted(Comparator.comparing(OptimizationExperiment::updatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<OptimizationExperiment> find(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) return Optional.empty();
        return load().stream().filter(value -> value.experimentId().equals(experimentId.trim())).findFirst();
    }

    @Override
    public Optional<OptimizationExperiment> findActiveByWorkItemId(String workItemId) {
        if (workItemId == null || workItemId.isBlank()) return Optional.empty();
        return load().stream().filter(value -> value.workItemId().equals(workItemId.trim()) && !OptimizationExperimentStatus.isTerminal(value.status())).findFirst();
    }

    @Override
    public OptimizationExperiment create(OptimizationExperiment value) {
        if (value == null) throw new IllegalArgumentException("experiment must not be null");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<OptimizationExperiment> values = current == null ? new ArrayList<>() : parse(current);
                if (values.stream().anyMatch(existing -> existing.experimentId().equals(value.experimentId()))) throw new OptimizationExperimentConflictException("experimentId already exists");
                ensureNoActiveExperiment(values, value);
                values.add(value);
                write(values, current);
                return value;
            });
        } catch (OptimizationExperimentConflictException | OptimizationExperimentPersistenceException exception) { throw exception; }
        catch (RuntimeException exception) { throw persistence(exception); }
    }

    @Override
    public OptimizationExperiment replace(OptimizationExperiment value) {
        if (value == null) throw new IllegalArgumentException("experiment must not be null");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<OptimizationExperiment> values = current == null ? List.of() : parse(current);
                int index = indexOf(values, value.experimentId());
                if (index < 0) throw new OptimizationExperimentNotFoundException(value.experimentId());
                OptimizationExperiment existing = values.get(index);
                if (existing.decision() != null && !existing.decision().equals(value.decision())) throw new OptimizationExperimentConflictException("decision snapshot is immutable");
                List<OptimizationExperiment> next = new ArrayList<>(values);
                next.remove(index);
                ensureNoActiveExperiment(next, value);
                next.add(value);
                write(next, current);
                return value;
            });
        } catch (OptimizationExperimentConflictException | OptimizationExperimentNotFoundException | OptimizationExperimentPersistenceException exception) { throw exception; }
        catch (RuntimeException exception) { throw persistence(exception); }
    }

    private void ensureNoActiveExperiment(List<OptimizationExperiment> values, OptimizationExperiment value) {
        if (OptimizationExperimentStatus.isTerminal(value.status())) return;
        if (values.stream().anyMatch(existing -> !OptimizationExperimentStatus.isTerminal(existing.status()) && existing.workItemId().equals(value.workItemId()))) throw new OptimizationExperimentConflictException("active experiment already exists for work item");
    }

    private List<OptimizationExperiment> load() {
        try {
            List<Row> rows = jdbc.query(SELECT, this::row, KEY);
            if (rows.isEmpty()) return List.of();
            if (rows.size() != 1) throw new IllegalArgumentException("optimization experiment aggregate is invalid");
            return parse(rows.getFirst());
        } catch (RuntimeException exception) { throw persistence(exception); }
    }

    private Row locked() {
        List<Row> rows = jdbc.query(SELECT + " for update", this::row, KEY);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException("optimization experiment aggregate is invalid");
        return rows.getFirst();
    }

    private void write(List<OptimizationExperiment> values, Row current) {
        long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
        String payload = serialize(values);
        if (current == null) {
            jdbc.update("insert into department_json_documents (document_key, document_schema_version, revision, payload) values (?, ?, ?, ?)", KEY, SCHEMA_VERSION, revision, payload);
            return;
        }
        int updated = jdbc.update("update department_json_documents set document_schema_version = ?, revision = ?, payload = ?, updated_at = current_timestamp where document_key = ? and revision = ?", SCHEMA_VERSION, revision, payload, KEY, current.revision());
        if (updated != 1) throw new OptimizationExperimentConflictException("optimization experiment revision conflict");
    }

    private List<OptimizationExperiment> parse(Row row) {
        if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) throw new IllegalArgumentException("optimization experiment aggregate is invalid");
        try {
            List<OptimizationExperiment> loaded = mapper.readValue(row.payload(), new TypeReference<>() { });
            List<OptimizationExperiment> values = List.copyOf(loaded == null ? List.of() : loaded);
            Set<String> ids = new HashSet<>();
            List<OptimizationExperiment> checked = new ArrayList<>();
            for (OptimizationExperiment value : values) {
                if (!ids.add(value.experimentId())) throw new IllegalArgumentException("duplicate experimentId");
                ensureNoActiveExperiment(checked, value);
                checked.add(value);
            }
            return values;
        } catch (JsonProcessingException | IllegalArgumentException exception) { throw new IllegalArgumentException("optimization experiment aggregate is invalid", exception); }
    }

    private int indexOf(List<OptimizationExperiment> values, String id) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).experimentId().equals(id)) return i;
        return -1;
    }

    private String serialize(List<OptimizationExperiment> values) {
        try { return mapper.writeValueAsString(values); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("optimization experiments cannot be serialized", exception); }
    }

    private Row row(ResultSet resultSet, int ignored) throws SQLException {
        return new Row(resultSet.getInt("document_schema_version"), resultSet.getLong("revision"), resultSet.getString("payload"));
    }

    private OptimizationExperimentPersistenceException persistence(Throwable cause) {
        if (cause instanceof OptimizationExperimentPersistenceException exception) return exception;
        return new OptimizationExperimentPersistenceException(cause);
    }

    private static <T> T require(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " is required"); return value; }
    private record Row(int schemaVersion, long revision, String payload) { }
}
