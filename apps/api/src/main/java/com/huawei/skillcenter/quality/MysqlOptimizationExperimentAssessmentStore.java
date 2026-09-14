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

/** MySQL JSON document persistence for department experiment assessments. */
@Component
@Conditional(OptimizationExperimentBackendCondition.Mysql.class)
public class MysqlOptimizationExperimentAssessmentStore implements OptimizationExperimentAssessmentRepository {
    private static final String KEY = "optimization-experiment-assessments";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload from department_json_documents where document_key = ?";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public MysqlOptimizationExperimentAssessmentStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate"); this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<OptimizationExperimentAssessment> findAll(String experimentId) {
        return load().stream().filter(value -> experimentId == null || experimentId.isBlank() || experimentId.equals(value.experimentId()))
                .sorted(Comparator.comparing(OptimizationExperimentAssessment::assessedAt).reversed()).toList();
    }
    @Override
    public Optional<OptimizationExperimentAssessment> find(String assessmentId) {
        if (assessmentId == null || assessmentId.isBlank()) return Optional.empty();
        return load().stream().filter(value -> value.assessmentId().equals(assessmentId.trim())).findFirst();
    }
    @Override
    public long countBefore(Instant cutoff) { if (cutoff == null) throw new IllegalArgumentException("cutoff must not be null"); return load().stream().filter(value -> value.assessedAt().isBefore(cutoff)).count(); }
    @Override
    public long deleteBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff must not be null");
        try { return transactions.execute(status -> { Row current = locked(); if (current == null) return 0L; List<OptimizationExperimentAssessment> before = parse(current); List<OptimizationExperimentAssessment> retained = before.stream().filter(value -> !value.assessedAt().isBefore(cutoff)).toList(); long deleted = before.size() - retained.size(); if (deleted > 0) write(retained, current); return deleted; }); }
        catch (OptimizationExperimentAssessmentStore.OptimizationExperimentAssessmentPersistenceException exception) { throw exception; }
        catch (RuntimeException exception) { throw persistence(exception); }
    }
    @Override
    public OptimizationExperimentAssessment create(OptimizationExperimentAssessment value) {
        if (value == null) throw new IllegalArgumentException("assessment must not be null");
        try { return transactions.execute(status -> { Row current = locked(); List<OptimizationExperimentAssessment> values = current == null ? new ArrayList<>() : parse(current); if (values.stream().anyMatch(existing -> existing.assessmentId().equals(value.assessmentId()))) throw new OptimizationExperimentConflictException("assessmentId already exists"); values.add(value); write(values, current); return value; }); }
        catch (OptimizationExperimentConflictException | OptimizationExperimentAssessmentStore.OptimizationExperimentAssessmentPersistenceException exception) { throw exception; }
        catch (RuntimeException exception) { throw persistence(exception); }
    }
    private List<OptimizationExperimentAssessment> load() { try { List<Row> rows = jdbc.query(SELECT, this::row, KEY); if (rows.isEmpty()) return List.of(); if (rows.size() != 1) throw new IllegalArgumentException("assessment aggregate is invalid"); return parse(rows.getFirst()); } catch (RuntimeException exception) { throw persistence(exception); } }
    private Row locked() { List<Row> rows = jdbc.query(SELECT + " for update", this::row, KEY); if (rows.isEmpty()) return null; if (rows.size() != 1) throw new IllegalArgumentException("assessment aggregate is invalid"); return rows.getFirst(); }
    private void write(List<OptimizationExperimentAssessment> values, Row current) { long revision = current == null ? 1 : Math.addExact(current.revision(), 1); String payload = serialize(values); if (current == null) { jdbc.update("insert into department_json_documents (document_key, document_schema_version, revision, payload) values (?, ?, ?, ?)", KEY, SCHEMA_VERSION, revision, payload); return; } int updated = jdbc.update("update department_json_documents set document_schema_version = ?, revision = ?, payload = ?, updated_at = current_timestamp where document_key = ? and revision = ?", SCHEMA_VERSION, revision, payload, KEY, current.revision()); if (updated != 1) throw new OptimizationExperimentConflictException("assessment revision conflict"); }
    private List<OptimizationExperimentAssessment> parse(Row row) { if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) throw new IllegalArgumentException("assessment aggregate is invalid"); try { List<OptimizationExperimentAssessment> loaded = mapper.readValue(row.payload(), new TypeReference<>() { }); List<OptimizationExperimentAssessment> values = List.copyOf(loaded == null ? List.of() : loaded); Set<String> ids = new HashSet<>(); for (OptimizationExperimentAssessment value : values) if (value == null || !ids.add(value.assessmentId())) throw new IllegalArgumentException("duplicate assessmentId"); return values; } catch (JsonProcessingException | IllegalArgumentException exception) { throw new IllegalArgumentException("assessment aggregate is invalid", exception); } }
    private String serialize(List<OptimizationExperimentAssessment> values) { try { return mapper.writeValueAsString(values); } catch (JsonProcessingException exception) { throw new IllegalArgumentException("assessments cannot be serialized", exception); } }
    private Row row(ResultSet resultSet, int ignored) throws SQLException { return new Row(resultSet.getInt("document_schema_version"), resultSet.getLong("revision"), resultSet.getString("payload")); }
    private OptimizationExperimentAssessmentStore.OptimizationExperimentAssessmentPersistenceException persistence(Throwable cause) { return cause instanceof OptimizationExperimentAssessmentStore.OptimizationExperimentAssessmentPersistenceException exception ? exception : new OptimizationExperimentAssessmentStore.OptimizationExperimentAssessmentPersistenceException(cause); }
    private static <T> T require(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " is required"); return value; }
    private record Row(int schemaVersion, long revision, String payload) { }
}
