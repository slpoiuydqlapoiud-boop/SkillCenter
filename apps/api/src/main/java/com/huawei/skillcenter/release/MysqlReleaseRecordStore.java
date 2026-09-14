package com.huawei.skillcenter.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
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

/** MySQL JSON document persistence for department controlled release records. */
@Component
@Conditional(ReleaseBackendCondition.Mysql.class)
public class MysqlReleaseRecordStore implements ReleaseRecordRepository {
    private static final String KEY = "release-records";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload from department_json_documents where document_key = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public MysqlReleaseRecordStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<ReleaseRecord> findAll(String skillId, String version, ReleaseEnvironment environment, ReleaseStatus status) {
        return load().stream()
                .filter(value -> skillId == null || skillId.isBlank() || skillId.equals(value.skillId()))
                .filter(value -> version == null || version.isBlank() || version.equals(value.version()))
                .filter(value -> environment == null || environment == value.targetEnvironment())
                .filter(value -> status == null || status == value.status())
                .sorted(Comparator.comparing(ReleaseRecord::updatedAt).reversed().thenComparing(ReleaseRecord::releaseId))
                .toList();
    }

    @Override
    public Optional<ReleaseRecord> find(String releaseId) {
        if (releaseId == null || releaseId.isBlank()) return Optional.empty();
        return load().stream().filter(value -> value.releaseId().equals(releaseId.trim())).findFirst();
    }

    @Override
    public Optional<ReleaseRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return load().stream().filter(value -> value.idempotencyKey().equals(idempotencyKey.trim())).findFirst();
    }

    @Override
    public Optional<ReleaseRecord> findActiveBusinessKey(String skillId, String version, ReleaseEnvironment environment) {
        if (skillId == null || skillId.isBlank() || version == null || version.isBlank() || environment == null) return Optional.empty();
        return load().stream().filter(value -> value.skillId().equals(skillId.trim())
                && value.version().equals(version.trim()) && value.targetEnvironment() == environment
                && !value.status().terminal()).findFirst();
    }

    @Override
    public ReleaseRecord create(ReleaseRecord value) {
        if (value == null) throw new IllegalArgumentException("release must not be null");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<ReleaseRecord> values = current == null ? new ArrayList<>() : parse(current);
                ensureNoDuplicate(values, value);
                values.add(value);
                write(values, current);
                return value;
            });
        } catch (ReleaseConflictException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReleasePersistenceException(exception);
        }
    }

    @Override
    public ReleaseRecord replace(ReleaseRecord value) {
        if (value == null) throw new IllegalArgumentException("release must not be null");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<ReleaseRecord> values = current == null ? new ArrayList<>() : parse(current);
                int index = indexOf(values, value.releaseId());
                if (index < 0) throw new IllegalArgumentException("release does not exist");
                ReleaseRecord existing = values.get(index);
                ensureImmutableContext(existing, value);
                values.remove(index);
                ensureNoDuplicate(values, value);
                values.add(value);
                write(values, current);
                return value;
            });
        } catch (ReleaseConflictException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReleasePersistenceException(exception);
        }
    }

    private List<ReleaseRecord> load() {
        try {
            List<Row> rows = jdbc.query(SELECT, this::row, KEY);
            if (rows.isEmpty()) return List.of();
            if (rows.size() != 1) throw new IllegalArgumentException("release aggregate is invalid");
            return parse(rows.getFirst());
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private Row locked() {
        List<Row> rows = jdbc.query(SELECT + " for update", this::row, KEY);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException("release aggregate is invalid");
        return rows.getFirst();
    }

    private void write(List<ReleaseRecord> values, Row current) {
        long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
        String payload = serialize(values);
        if (current == null) {
            jdbc.update("insert into department_json_documents (document_key, document_schema_version, revision, payload) values (?, ?, ?, ?)", KEY, SCHEMA_VERSION, revision, payload);
            return;
        }
        int updated = jdbc.update("update department_json_documents set document_schema_version = ?, revision = ?, payload = ?, updated_at = current_timestamp where document_key = ? and revision = ?", SCHEMA_VERSION, revision, payload, KEY, current.revision());
        if (updated != 1) throw new ReleaseConflictException("release revision conflict");
    }

    private List<ReleaseRecord> parse(Row row) {
        if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) throw new IllegalArgumentException("release aggregate is invalid");
        try {
            List<ReleaseRecord> loaded = mapper.readValue(row.payload(), new TypeReference<>() { });
            List<ReleaseRecord> values = List.copyOf(loaded == null ? List.of() : loaded);
            Set<String> releaseIds = new HashSet<>();
            Set<String> idempotencyKeys = new HashSet<>();
            List<ReleaseRecord> checked = new ArrayList<>();
            for (ReleaseRecord value : values) {
                if (value == null || !releaseIds.add(value.releaseId()) || !idempotencyKeys.add(value.idempotencyKey())) throw new IllegalArgumentException("duplicate release identity");
                ensureNoDuplicate(checked, value);
                checked.add(value);
            }
            return values;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("release aggregate is invalid", exception);
        }
    }

    private void ensureNoDuplicate(List<ReleaseRecord> values, ReleaseRecord value) {
        if (values.stream().anyMatch(existing -> existing.releaseId().equals(value.releaseId()))) throw new ReleaseConflictException("releaseId already exists");
        if (values.stream().anyMatch(existing -> existing.idempotencyKey().equals(value.idempotencyKey()))) throw new ReleaseConflictException("idempotencyKey already exists");
        if (!value.status().terminal() && values.stream().anyMatch(existing -> !existing.status().terminal()
                && existing.skillId().equals(value.skillId()) && existing.version().equals(value.version())
                && existing.targetEnvironment() == value.targetEnvironment())) throw new ReleaseConflictException("active release already exists for skill version and environment");
    }

    private void ensureImmutableContext(ReleaseRecord existing, ReleaseRecord value) {
        if (!existing.skillId().equals(value.skillId()) || !existing.version().equals(value.version())
                || !existing.sha256().equals(value.sha256()) || existing.targetEnvironment() != value.targetEnvironment()
                || !existing.gateSnapshot().equals(value.gateSnapshot()) || !existing.sourceAssessmentId().equals(value.sourceAssessmentId())
                || !existing.rollbackOfReleaseId().equals(value.rollbackOfReleaseId()) || !existing.rollbackTargetVersion().equals(value.rollbackTargetVersion())
                || !existing.rollbackTargetReleaseId().equals(value.rollbackTargetReleaseId()) || !existing.idempotencyKey().equals(value.idempotencyKey())
                || !existing.requestedBy().equals(value.requestedBy()) || !existing.requestedAt().equals(value.requestedAt())) throw new ReleaseConflictException("release context is immutable");
    }

    private int indexOf(List<ReleaseRecord> values, String id) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).releaseId().equals(id)) return i;
        return -1;
    }

    private String serialize(List<ReleaseRecord> values) {
        try { return mapper.writeValueAsString(values); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("release aggregate cannot be serialized", exception); }
    }

    private Row row(ResultSet rs, int ignored) throws SQLException { return new Row(rs.getInt("document_schema_version"), rs.getLong("revision"), rs.getString("payload")); }
    private ReleasePersistenceException failure(Throwable cause) { return cause instanceof ReleasePersistenceException value ? value : new ReleasePersistenceException(cause); }
    private static <T> T require(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " is required"); return value; }
    private record Row(int schemaVersion, long revision, String payload) { }
}
