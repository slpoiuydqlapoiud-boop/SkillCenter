package com.huawei.skillcenter.relationship;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** MySQL JSON document persistence for department Skill version relationships. */
@Component
@Conditional(SkillRelationBackendCondition.Mysql.class)
public class MysqlSkillRelationStore implements SkillRelationRepository {
    private static final String KEY = "skill-relations";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload from department_json_documents where document_key = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public MysqlSkillRelationStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public Optional<SkillRelation> find(String relationId) {
        if (relationId == null || relationId.isBlank()) return Optional.empty();
        String normalized = relationId.trim();
        return load().stream().filter(value -> value.relationId().equals(normalized)).findFirst();
    }

    @Override
    public List<SkillRelation> findAll(String sourceSkillId, String sourceVersion, String targetSkillId,
                                       String targetVersion, SkillRelationStatus status) {
        return load().stream()
                .filter(value -> matches(sourceSkillId, value.sourceSkillId()))
                .filter(value -> matches(sourceVersion, value.sourceVersion()))
                .filter(value -> matches(targetSkillId, value.targetSkillId()))
                .filter(value -> matches(targetVersion, value.targetVersion()))
                .filter(value -> status == null || value.status() == status)
                .sorted(Comparator.comparing(SkillRelation::declaredAt).thenComparing(SkillRelation::relationId))
                .toList();
    }

    @Override
    public SkillRelation create(SkillRelation relation) {
        if (relation == null) throw new IllegalArgumentException("relation is required");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<SkillRelation> values = current == null ? new ArrayList<>() : parse(current);
                ensureNoDuplicate(values, relation);
                List<SkillRelation> next = new ArrayList<>(values);
                next.add(relation);
                validateAcyclic(next);
                write(next, current);
                return relation;
            });
        } catch (SkillRelationConflictException | SkillRelationPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public SkillRelation replace(SkillRelation relation) {
        if (relation == null) throw new IllegalArgumentException("relation is required");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<SkillRelation> values = current == null ? List.of() : parse(current);
                int index = indexOf(values, relation.relationId());
                if (index < 0) throw new SkillRelationConflictException("relation does not exist");
                SkillRelation existing = values.get(index);
                ensureImmutableContext(existing, relation);
                List<SkillRelation> next = new ArrayList<>(values);
                next.remove(index);
                ensureNoDuplicate(next, relation);
                next.add(relation);
                validateAcyclic(next);
                write(next, current);
                return relation;
            });
        } catch (SkillRelationConflictException | SkillRelationPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private List<SkillRelation> load() {
        try {
            List<Row> rows = jdbc.query(SELECT, this::row, KEY);
            if (rows.isEmpty()) return List.of();
            if (rows.size() != 1) throw new IllegalArgumentException("skill relation aggregate is invalid");
            return parse(rows.getFirst());
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private Row locked() {
        List<Row> rows = jdbc.query(SELECT + " for update", this::row, KEY);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException("skill relation aggregate is invalid");
        return rows.getFirst();
    }

    private void write(List<SkillRelation> values, Row current) {
        long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
        String payload = serialize(values);
        if (current == null) {
            jdbc.update("insert into department_json_documents (document_key, document_schema_version, revision, payload) values (?, ?, ?, ?)",
                    KEY, SCHEMA_VERSION, revision, payload);
            return;
        }
        int updated = jdbc.update("update department_json_documents set document_schema_version = ?, revision = ?, payload = ?, updated_at = current_timestamp where document_key = ? and revision = ?",
                SCHEMA_VERSION, revision, payload, KEY, current.revision());
        if (updated != 1) throw new SkillRelationConflictException("relation revision conflict");
    }

    private List<SkillRelation> parse(Row row) {
        if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) {
            throw new IllegalArgumentException("skill relation aggregate is invalid");
        }
        try {
            List<SkillRelation> loaded = mapper.readValue(row.payload(), new TypeReference<>() { });
            List<SkillRelation> values = new ArrayList<>(loaded == null ? List.of() : loaded);
            List<SkillRelation> checked = new ArrayList<>();
            for (SkillRelation value : values) {
                if (value == null) throw new IllegalArgumentException("relation must not be null");
                ensureNoDuplicate(checked, value);
                checked.add(value);
            }
            validateAcyclic(checked);
            return List.copyOf(checked);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("skill relation aggregate is invalid", exception);
        }
    }

    private void ensureNoDuplicate(List<SkillRelation> values, SkillRelation relation) {
        if (values.stream().anyMatch(value -> value.relationId().equals(relation.relationId()))) {
            throw new SkillRelationConflictException("relationId already exists");
        }
        if (relation.status() == SkillRelationStatus.ACTIVE && values.stream().anyMatch(value ->
                value.status() == SkillRelationStatus.ACTIVE
                        && value.sourceSkillId().equals(relation.sourceSkillId())
                        && value.sourceVersion().equals(relation.sourceVersion())
                        && value.targetSkillId().equals(relation.targetSkillId())
                        && value.targetVersion().equals(relation.targetVersion())
                        && value.relationType() == relation.relationType())) {
            throw new SkillRelationConflictException("active relation already exists");
        }
    }

    private void ensureImmutableContext(SkillRelation existing, SkillRelation relation) {
        if (!existing.relationId().equals(relation.relationId())
                || !existing.sourceSkillId().equals(relation.sourceSkillId())
                || !existing.sourceVersion().equals(relation.sourceVersion())
                || !existing.targetSkillId().equals(relation.targetSkillId())
                || !existing.targetVersion().equals(relation.targetVersion())
                || existing.relationType() != relation.relationType()
                || !existing.declaredBy().equals(relation.declaredBy())
                || !existing.declaredAt().equals(relation.declaredAt())) {
            throw new SkillRelationConflictException("relation context is immutable");
        }
    }

    private void validateAcyclic(List<SkillRelation> values) {
        Map<String, Set<String>> graph = new HashMap<>();
        values.stream().filter(value -> value.status() == SkillRelationStatus.ACTIVE).forEach(value ->
                graph.computeIfAbsent(SkillRelationStore.key(value.sourceSkillId(), value.sourceVersion()), ignored -> new HashSet<>())
                        .add(SkillRelationStore.key(value.targetSkillId(), value.targetVersion())));
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String node : graph.keySet()) if (hasCycle(node, graph, visiting, visited)) {
            throw new SkillRelationConflictException("active relation graph contains a cycle");
        }
    }

    private boolean hasCycle(String node, Map<String, Set<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) return true;
        if (!visited.add(node)) return false;
        visiting.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) if (hasCycle(next, graph, visiting, visited)) return true;
        visiting.remove(node);
        return false;
    }

    private int indexOf(List<SkillRelation> values, String relationId) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).relationId().equals(relationId)) return i;
        return -1;
    }

    private static boolean matches(String filter, String value) {
        return filter == null || filter.isBlank() || filter.trim().equals(value);
    }

    private String serialize(List<SkillRelation> values) {
        try { return mapper.writeValueAsString(values); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("skill relations cannot be serialized", exception); }
    }

    private Row row(ResultSet resultSet, int ignored) throws SQLException {
        return new Row(resultSet.getInt("document_schema_version"), resultSet.getLong("revision"), resultSet.getString("payload"));
    }

    private SkillRelationPersistenceException persistence(Throwable cause) {
        if (cause instanceof SkillRelationPersistenceException exception) return exception;
        return new SkillRelationPersistenceException(cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record Row(int schemaVersion, long revision, String payload) { }
}
