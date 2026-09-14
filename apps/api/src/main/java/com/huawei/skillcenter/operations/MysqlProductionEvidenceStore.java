package com.huawei.skillcenter.operations;

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
import java.util.List;
import java.util.Optional;

/** MySQL JSON document persistence for safe department production handoff evidence. */
@Component
@Conditional(ProductionEvidenceBackendCondition.Mysql.class)
public class MysqlProductionEvidenceStore implements ProductionEvidenceRepository {
    private static final String KEY = "production-evidence";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload from department_json_documents where document_key = ?";
    private static final Comparator<ProductionEvidence> BY_ID = Comparator.comparing(ProductionEvidence::evidenceId);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public MysqlProductionEvidenceStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<ProductionEvidence> findAll() {
        return load();
    }

    @Override
    public Optional<ProductionEvidence> find(String evidenceId) {
        if (evidenceId == null || evidenceId.isBlank()) return Optional.empty();
        String normalized = ProductionEvidenceCatalog.requireId(evidenceId);
        return load().stream().filter(value -> value.evidenceId().equals(normalized)).findFirst();
    }

    @Override
    public ProductionEvidence upsert(ProductionEvidence evidence, int expectedRevision) {
        if (evidence == null) throw new IllegalArgumentException("production evidence is required");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<ProductionEvidence> values = current == null ? new ArrayList<>() : parse(current);
                int index = indexOf(values, evidence.evidenceId());
                int currentRevision = index < 0 ? 0 : values.get(index).revision();
                if (currentRevision != expectedRevision) throw new ProductionEvidenceConflictException("production evidence revision conflict");
                if (evidence.revision() != expectedRevision + 1) throw new ProductionEvidenceConflictException("production evidence revision must advance by one");
                List<ProductionEvidence> next = new ArrayList<>(values);
                if (index < 0) next.add(evidence); else next.set(index, evidence);
                List<ProductionEvidence> sorted = sortAndValidate(next);
                write(sorted, current);
                return evidence;
            });
        } catch (ProductionEvidenceConflictException | ProductionEvidencePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private List<ProductionEvidence> load() {
        try {
            List<Row> rows = jdbc.query(SELECT, this::row, KEY);
            if (rows.isEmpty()) return List.of();
            if (rows.size() != 1) throw new IllegalArgumentException("production evidence aggregate is invalid");
            return parse(rows.getFirst());
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private Row locked() {
        List<Row> rows = jdbc.query(SELECT + " for update", this::row, KEY);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException("production evidence aggregate is invalid");
        return rows.getFirst();
    }

    private void write(List<ProductionEvidence> values, Row current) {
        long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
        String payload = serialize(values);
        if (current == null) {
            jdbc.update("insert into department_json_documents (document_key, document_schema_version, revision, payload) values (?, ?, ?, ?)", KEY, SCHEMA_VERSION, revision, payload);
            return;
        }
        int updated = jdbc.update("update department_json_documents set document_schema_version = ?, revision = ?, payload = ?, updated_at = current_timestamp where document_key = ? and revision = ?", SCHEMA_VERSION, revision, payload, KEY, current.revision());
        if (updated != 1) throw new ProductionEvidenceConflictException("production evidence revision conflict");
    }

    private List<ProductionEvidence> parse(Row row) {
        if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) throw new IllegalArgumentException("production evidence aggregate is invalid");
        try {
            List<ProductionEvidence> loaded = mapper.readValue(row.payload(), new TypeReference<>() { });
            return sortAndValidate(loaded == null ? List.of() : loaded);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("production evidence aggregate is invalid", exception);
        }
    }

    private List<ProductionEvidence> sortAndValidate(List<ProductionEvidence> values) {
        List<ProductionEvidence> sorted = new ArrayList<>(values == null ? List.of() : values);
        sorted.sort(BY_ID);
        for (int index = 0; index < sorted.size(); index++) {
            ProductionEvidence value = sorted.get(index);
            if (value == null) throw new IllegalArgumentException("production evidence must not be null");
            if (index > 0 && sorted.get(index - 1).evidenceId().equals(value.evidenceId())) throw new IllegalArgumentException("duplicate production evidence id");
        }
        return List.copyOf(sorted);
    }

    private int indexOf(List<ProductionEvidence> values, String evidenceId) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).evidenceId().equals(evidenceId)) return i;
        return -1;
    }

    private String serialize(List<ProductionEvidence> values) {
        try { return mapper.writeValueAsString(values); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("production evidence cannot be serialized", exception); }
    }

    private Row row(ResultSet resultSet, int ignored) throws SQLException {
        return new Row(resultSet.getInt("document_schema_version"), resultSet.getLong("revision"), resultSet.getString("payload"));
    }

    private ProductionEvidencePersistenceException persistence(Throwable cause) {
        if (cause instanceof ProductionEvidencePersistenceException exception) return exception;
        return new ProductionEvidencePersistenceException("Unable to access production evidence state", cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record Row(int schemaVersion, long revision, String payload) { }
}
