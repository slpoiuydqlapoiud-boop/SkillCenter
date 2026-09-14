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
import java.util.Optional;
import java.util.Set;

/** MySQL JSON document persistence for department experiment runtime observations. */
@Component
@Conditional(OptimizationExperimentBackendCondition.Mysql.class)
public class MysqlOptimizationExperimentObservationStore implements OptimizationExperimentObservationRepository {
    private static final String KEY = "optimization-experiment-observations";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload from department_json_documents where document_key = ?";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public MysqlOptimizationExperimentObservationStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate"); this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<OptimizationExperimentObservation> findAll(String experimentId) {
        return load().stream().filter(value -> experimentId == null || experimentId.isBlank() || experimentId.equals(value.experimentId()))
                .sorted(Comparator.comparing(OptimizationExperimentObservation::capturedAt).reversed()).toList();
    }

    @Override
    public Optional<OptimizationExperimentObservation> find(String observationId) {
        if (observationId == null || observationId.isBlank()) return Optional.empty();
        return load().stream().filter(value -> value.observationId().equals(observationId.trim())).findFirst();
    }

    @Override
    public long countBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff must not be null");
        return load().stream().filter(value -> value.capturedAt().isBefore(cutoff)).count();
    }

    @Override
    public long deleteBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff must not be null");
        try {
            return transactions.execute(status -> {
                Row current = locked(); if (current == null) return 0L;
                List<OptimizationExperimentObservation> before = parse(current);
                List<OptimizationExperimentObservation> retained = before.stream().filter(value -> !value.capturedAt().isBefore(cutoff)).toList();
                long deleted = before.size() - retained.size(); if (deleted > 0) write(retained, current); return deleted;
            });
        } catch (OptimizationExperimentPersistenceException exception) { throw exception; }
        catch (RuntimeException exception) { throw persistence(exception); }
    }

    @Override
    public OptimizationExperimentObservation create(OptimizationExperimentObservation value) {
        if (value == null) throw new IllegalArgumentException("observation must not be null");
        try {
            return transactions.execute(status -> {
                Row current = locked(); List<OptimizationExperimentObservation> values = current == null ? new ArrayList<>() : parse(current);
                if (values.stream().anyMatch(existing -> existing.observationId().equals(value.observationId()))) throw new OptimizationExperimentConflictException("observationId already exists");
                values.add(value); write(values, current); return value;
            });
        } catch (OptimizationExperimentConflictException | OptimizationExperimentPersistenceException exception) { throw exception; }
        catch (RuntimeException exception) { throw persistence(exception); }
    }

    private List<OptimizationExperimentObservation> load() {
        try { List<Row> rows = jdbc.query(SELECT, this::row, KEY); if (rows.isEmpty()) return List.of(); if (rows.size() != 1) throw new IllegalArgumentException("observation aggregate is invalid"); return parse(rows.getFirst()); }
        catch (RuntimeException exception) { throw persistence(exception); }
    }
    private Row locked() { List<Row> rows = jdbc.query(SELECT + " for update", this::row, KEY); if (rows.isEmpty()) return null; if (rows.size() != 1) throw new IllegalArgumentException("observation aggregate is invalid"); return rows.getFirst(); }
    private void write(List<OptimizationExperimentObservation> values, Row current) {
        long revision = current == null ? 1 : Math.addExact(current.revision(), 1); String payload = serialize(values);
        if (current == null) { jdbc.update("insert into department_json_documents (document_key, document_schema_version, revision, payload) values (?, ?, ?, ?)", KEY, SCHEMA_VERSION, revision, payload); return; }
        int updated = jdbc.update("update department_json_documents set document_schema_version = ?, revision = ?, payload = ?, updated_at = current_timestamp where document_key = ? and revision = ?", SCHEMA_VERSION, revision, payload, KEY, current.revision());
        if (updated != 1) throw new OptimizationExperimentConflictException("observation revision conflict");
    }
    private List<OptimizationExperimentObservation> parse(Row row) {
        if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) throw new IllegalArgumentException("observation aggregate is invalid");
        try { List<OptimizationExperimentObservation> loaded = mapper.readValue(row.payload(), new TypeReference<>() { }); List<OptimizationExperimentObservation> values = List.copyOf(loaded == null ? List.of() : loaded); Set<String> ids = new HashSet<>(); for (OptimizationExperimentObservation value : values) if (value == null || !ids.add(value.observationId())) throw new IllegalArgumentException("duplicate observationId"); return values; }
        catch (JsonProcessingException | IllegalArgumentException exception) { throw new IllegalArgumentException("observation aggregate is invalid", exception); }
    }
    private String serialize(List<OptimizationExperimentObservation> values) { try { return mapper.writeValueAsString(values); } catch (JsonProcessingException exception) { throw new IllegalArgumentException("observations cannot be serialized", exception); } }
    private Row row(ResultSet resultSet, int ignored) throws SQLException { return new Row(resultSet.getInt("document_schema_version"), resultSet.getLong("revision"), resultSet.getString("payload")); }
    private OptimizationExperimentPersistenceException persistence(Throwable cause) { return cause instanceof OptimizationExperimentPersistenceException exception ? exception : new OptimizationExperimentPersistenceException(cause); }
    private static <T> T require(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " is required"); return value; }
    private record Row(int schemaVersion, long revision, String payload) { }
}
