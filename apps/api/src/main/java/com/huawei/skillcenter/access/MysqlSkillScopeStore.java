package com.huawei.skillcenter.access;

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

/** MySQL JSON document persistence for department Skill scope metadata. */
@Component
@Conditional(SkillScopeBackendCondition.Mysql.class)
public class MysqlSkillScopeStore implements SkillScopeRepository {
    private static final String KEY = "skill-scopes";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload from department_json_documents where document_key = ?";
    private static final Comparator<SkillScope> BY_SKILL_ID = Comparator.comparing(SkillScope::skillId);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public MysqlSkillScopeStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public Optional<SkillScope> find(String skillId) {
        if (skillId == null || skillId.isBlank()) return Optional.empty();
        String normalized = SkillScope.normalizeRequiredIdentifier(skillId, "skillId");
        return load().stream().filter(value -> value.skillId().equals(normalized)).findFirst();
    }

    @Override
    public List<SkillScope> findAll() {
        return load();
    }

    @Override
    public SkillScope create(SkillScope scope) {
        if (scope == null) throw new IllegalArgumentException("scope is required");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<SkillScope> values = current == null ? new ArrayList<>() : parse(current);
                if (values.stream().anyMatch(value -> value.skillId().equals(scope.skillId()))) {
                    throw new SkillScopeConflictException("skillId already exists");
                }
                values.add(scope);
                List<SkillScope> sorted = sortAndValidate(values);
                write(sorted, current);
                return scope;
            });
        } catch (SkillScopeConflictException | SkillScopePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public SkillScope replace(SkillScope scope, int expectedRevision) {
        if (scope == null) throw new IllegalArgumentException("scope is required");
        if (expectedRevision < 1) throw new SkillScopeConflictException("revision conflict");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<SkillScope> values = current == null ? List.of() : parse(current);
                int index = indexOf(values, scope.skillId());
                if (index < 0) throw new SkillScopeConflictException("skill scope does not exist");
                SkillScope existing = values.get(index);
                if (existing.revision() != expectedRevision) throw new SkillScopeConflictException("revision conflict");
                SkillScope updated = new SkillScope(existing.skillId(), scope.visibility(), scope.ownerTeamId(),
                        scope.maintainerUserIds(), expectedRevision + 1, existing.declaredBy(), existing.declaredAt(),
                        scope.updatedBy(), scope.updatedAt());
                List<SkillScope> next = new ArrayList<>(values);
                next.set(index, updated);
                List<SkillScope> sorted = sortAndValidate(next);
                write(sorted, current);
                return updated;
            });
        } catch (SkillScopeConflictException | SkillScopePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private List<SkillScope> load() {
        try {
            List<Row> rows = jdbc.query(SELECT, this::row, KEY);
            if (rows.isEmpty()) return List.of();
            if (rows.size() != 1) throw new IllegalArgumentException("skill scope aggregate is invalid");
            return parse(rows.getFirst());
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private Row locked() {
        List<Row> rows = jdbc.query(SELECT + " for update", this::row, KEY);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException("skill scope aggregate is invalid");
        return rows.getFirst();
    }

    private void write(List<SkillScope> values, Row current) {
        long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
        String payload = serialize(values);
        if (current == null) {
            jdbc.update("insert into department_json_documents (document_key, document_schema_version, revision, payload) values (?, ?, ?, ?)",
                    KEY, SCHEMA_VERSION, revision, payload);
            return;
        }
        int updated = jdbc.update("update department_json_documents set document_schema_version = ?, revision = ?, payload = ?, updated_at = current_timestamp where document_key = ? and revision = ?",
                SCHEMA_VERSION, revision, payload, KEY, current.revision());
        if (updated != 1) throw new SkillScopeConflictException("revision conflict");
    }

    private List<SkillScope> parse(Row row) {
        if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) {
            throw new IllegalArgumentException("skill scope aggregate is invalid");
        }
        try {
            List<SkillScope> loaded = mapper.readValue(row.payload(), new TypeReference<>() { });
            return sortAndValidate(loaded == null ? List.of() : loaded);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("skill scope aggregate is invalid", exception);
        }
    }

    private List<SkillScope> sortAndValidate(List<SkillScope> scopes) {
        List<SkillScope> normalized = new ArrayList<>(scopes == null ? List.of() : scopes);
        normalized.sort(BY_SKILL_ID);
        for (int index = 0; index < normalized.size(); index++) {
            SkillScope scope = normalized.get(index);
            if (scope == null) throw new IllegalArgumentException("skill scope must not be null");
            if (index > 0 && normalized.get(index - 1).skillId().equals(scope.skillId())) {
                throw new IllegalArgumentException("duplicate skillId");
            }
        }
        return List.copyOf(normalized);
    }

    private int indexOf(List<SkillScope> values, String skillId) {
        for (int index = 0; index < values.size(); index++) if (values.get(index).skillId().equals(skillId)) return index;
        return -1;
    }

    private String serialize(List<SkillScope> values) {
        try { return mapper.writeValueAsString(values); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("skill scopes cannot be serialized", exception); }
    }

    private Row row(ResultSet resultSet, int ignored) throws SQLException {
        return new Row(resultSet.getInt("document_schema_version"), resultSet.getLong("revision"), resultSet.getString("payload"));
    }

    private SkillScopePersistenceException persistence(Throwable cause) {
        if (cause instanceof SkillScopePersistenceException exception) return exception;
        return new SkillScopePersistenceException("Skill scope state is unavailable", cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record Row(int schemaVersion, long revision, String payload) { }
}
