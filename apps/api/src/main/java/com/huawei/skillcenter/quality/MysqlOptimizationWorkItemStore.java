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

/** MySQL JSON document persistence for department optimization work items. */
@Component
@Conditional(OptimizationWorkItemBackendCondition.Mysql.class)
public class MysqlOptimizationWorkItemStore implements OptimizationWorkItemRepository {
    private static final String KEY = "optimization-work-items";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload from department_json_documents where document_key = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public MysqlOptimizationWorkItemStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<OptimizationWorkItem> findAll(String skillId, String status, String ownerId, String sourceVersion) {
        return load().stream()
                .filter(value -> skillId == null || skillId.isBlank() || skillId.equals(value.skillId()))
                .filter(value -> status == null || status.isBlank() || OptimizationWorkItemStatus.normalize(status).equals(value.status()))
                .filter(value -> ownerId == null || ownerId.isBlank() || ownerId.equals(value.ownerId()))
                .filter(value -> sourceVersion == null || sourceVersion.isBlank() || sourceVersion.equals(value.sourceVersion()))
                .sorted(Comparator.comparing(OptimizationWorkItem::updatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<OptimizationWorkItem> find(String workItemId) {
        if (workItemId == null || workItemId.isBlank()) return Optional.empty();
        return load().stream().filter(value -> value.workItemId().equals(workItemId.trim())).findFirst();
    }

    @Override
    public OptimizationWorkItem create(OptimizationWorkItem value) {
        if (value == null) throw new IllegalArgumentException("work item must not be null");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<OptimizationWorkItem> values = current == null ? new ArrayList<>() : parse(current);
                if (values.stream().anyMatch(existing -> existing.workItemId().equals(value.workItemId()))) throw new OptimizationWorkItemConflictException("workItemId already exists");
                ensureNoActiveBusinessKey(values, value);
                values.add(value);
                write(values, current);
                return value;
            });
        } catch (OptimizationWorkItemConflictException | OptimizationWorkItemStore.OptimizationWorkItemPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public OptimizationWorkItem replace(OptimizationWorkItem value) {
        if (value == null) throw new IllegalArgumentException("work item must not be null");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<OptimizationWorkItem> values = current == null ? List.of() : parse(current);
                int index = indexOf(values, value.workItemId());
                if (index < 0) throw new OptimizationWorkItemNotFoundException(value.workItemId());
                List<OptimizationWorkItem> next = new ArrayList<>(values);
                next.remove(index);
                ensureNoActiveBusinessKey(next, value);
                next.add(value);
                write(next, current);
                return value;
            });
        } catch (OptimizationWorkItemStore.OptimizationWorkItemPersistenceException | OptimizationWorkItemNotFoundException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private void ensureNoActiveBusinessKey(List<OptimizationWorkItem> values, OptimizationWorkItem value) {
        if (OptimizationWorkItemStatus.isTerminal(value.status())) return;
        if (values.stream().anyMatch(existing -> !OptimizationWorkItemStatus.isTerminal(existing.status())
                && existing.skillId().equals(value.skillId()) && existing.sourceVersion().equals(value.sourceVersion())
                && existing.suggestionId().equals(value.suggestionId()))) throw new OptimizationWorkItemConflictException("active work item already exists");
    }

    private List<OptimizationWorkItem> load() {
        try {
            List<Row> rows = jdbc.query(SELECT, this::row, KEY);
            if (rows.isEmpty()) return List.of();
            if (rows.size() != 1) throw new IllegalArgumentException("optimization work item aggregate is invalid");
            return parse(rows.getFirst());
        } catch (RuntimeException exception) { throw persistence(exception); }
    }

    private Row locked() {
        List<Row> rows = jdbc.query(SELECT + " for update", this::row, KEY);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException("optimization work item aggregate is invalid");
        return rows.getFirst();
    }

    private void write(List<OptimizationWorkItem> values, Row current) {
        long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
        String payload = serialize(values);
        if (current == null) {
            jdbc.update("insert into department_json_documents (document_key, document_schema_version, revision, payload) values (?, ?, ?, ?)", KEY, SCHEMA_VERSION, revision, payload);
            return;
        }
        int updated = jdbc.update("update department_json_documents set document_schema_version = ?, revision = ?, payload = ?, updated_at = current_timestamp where document_key = ? and revision = ?", SCHEMA_VERSION, revision, payload, KEY, current.revision());
        if (updated != 1) throw new OptimizationWorkItemConflictException("optimization work item revision conflict");
    }

    private List<OptimizationWorkItem> parse(Row row) {
        if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) throw new IllegalArgumentException("optimization work item aggregate is invalid");
        try {
            List<OptimizationWorkItem> loaded = mapper.readValue(row.payload(), new TypeReference<>() { });
            List<OptimizationWorkItem> values = List.copyOf(loaded == null ? List.of() : loaded);
            Set<String> ids = new HashSet<>();
            List<OptimizationWorkItem> checked = new ArrayList<>();
            for (OptimizationWorkItem value : values) {
                if (!ids.add(value.workItemId())) throw new IllegalArgumentException("duplicate workItemId");
                ensureNoActiveBusinessKey(checked, value);
                checked.add(value);
            }
            return values;
        } catch (JsonProcessingException | IllegalArgumentException exception) { throw new IllegalArgumentException("optimization work item aggregate is invalid", exception); }
    }

    private int indexOf(List<OptimizationWorkItem> values, String id) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).workItemId().equals(id)) return i;
        return -1;
    }

    private String serialize(List<OptimizationWorkItem> values) {
        try { return mapper.writeValueAsString(values); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("optimization work items cannot be serialized", exception); }
    }

    private Row row(ResultSet resultSet, int ignored) throws SQLException {
        return new Row(resultSet.getInt("document_schema_version"), resultSet.getLong("revision"), resultSet.getString("payload"));
    }

    private OptimizationWorkItemStore.OptimizationWorkItemPersistenceException persistence(Throwable cause) {
        if (cause instanceof OptimizationWorkItemStore.OptimizationWorkItemPersistenceException exception) return exception;
        return new OptimizationWorkItemStore.OptimizationWorkItemPersistenceException(cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record Row(int schemaVersion, long revision, String payload) { }
}
