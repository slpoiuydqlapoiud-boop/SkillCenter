package com.huawei.skillcenter.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL persistence for shared Skill scope metadata. */
@Component
@Conditional(SkillScopeBackendCondition.Postgresql.class)
public class JdbcSkillScopeStore implements SkillScopeRepository {
    private static final String TABLE = "skill_scopes";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcSkillScopeStore(JdbcTemplate jdbc, ObjectMapper mapper,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public Optional<SkillScope> find(String skillId) {
        if (skillId == null || skillId.isBlank()) return Optional.empty();
        try {
            List<SkillScope> values = jdbc.query("select * from " + TABLE + " where skill_id = ?",
                    this::map, skillId.trim());
            return values.stream().findFirst();
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public List<SkillScope> findAll() {
        try {
            return jdbc.query("select * from " + TABLE + " order by skill_id", this::map);
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public SkillScope create(SkillScope scope) {
        require(scope, "scope");
        try {
            transactions.execute(status -> {
                try {
                    insert(scope);
                    return null;
                } catch (DuplicateKeyException exception) {
                    status.setRollbackOnly();
                    throw new SkillScopeConflictException("skillId already exists");
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw persistence(exception);
                }
            });
            return scope;
        } catch (SkillScopeConflictException | SkillScopePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public SkillScope replace(SkillScope scope, int expectedRevision) {
        require(scope, "scope");
        if (expectedRevision < 1) throw new SkillScopeConflictException("revision conflict");
        try {
            SkillScope updated = transactions.execute(status -> {
                try {
                    SkillScope existing = find(scope.skillId()).orElseThrow(
                            () -> new SkillScopeConflictException("skill scope does not exist"));
                    if (existing.revision() != expectedRevision) {
                        throw new SkillScopeConflictException("revision conflict");
                    }
                    SkillScope next = new SkillScope(existing.skillId(), scope.visibility(), scope.ownerTeamId(),
                            scope.maintainerUserIds(), expectedRevision + 1, existing.declaredBy(),
                            existing.declaredAt(), scope.updatedBy(), scope.updatedAt());
                    int count = jdbc.update("update " + TABLE + " set visibility = ?, owner_team_id = ?,"
                                    + " maintainer_user_ids = ?::jsonb, revision = ?, updated_by = ?, updated_at = ?"
                                    + " where skill_id = ? and revision = ?",
                            next.visibility().name(), nullable(next.ownerTeamId()), maintainers(next), next.revision(),
                            next.updatedBy(), Timestamp.from(next.updatedAt()), next.skillId(), expectedRevision);
                    if (count != 1) {
                        status.setRollbackOnly();
                        throw new SkillScopeConflictException("revision conflict");
                    }
                    return next;
                } catch (SkillScopeConflictException exception) {
                    status.setRollbackOnly();
                    throw exception;
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw persistence(exception);
                }
            });
            return updated;
        } catch (SkillScopeConflictException | SkillScopePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private void insert(SkillScope scope) {
        jdbc.update("insert into " + TABLE + " (skill_id, visibility, owner_team_id, maintainer_user_ids,"
                        + " revision, declared_by, declared_at, updated_by, updated_at)"
                        + " values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)",
                scope.skillId(), scope.visibility().name(), nullable(scope.ownerTeamId()), maintainers(scope),
                scope.revision(), scope.declaredBy(), Timestamp.from(scope.declaredAt()), scope.updatedBy(),
                Timestamp.from(scope.updatedAt()));
    }

    private SkillScope map(ResultSet resultSet, int ignored) throws SQLException {
        try {
            List<String> maintainers = mapper.readValue(resultSet.getString("maintainer_user_ids"),
                    new TypeReference<>() { });
            return new SkillScope(resultSet.getString("skill_id"),
                    SkillVisibility.valueOf(resultSet.getString("visibility")),
                    resultSet.getString("owner_team_id"), maintainers,
                    resultSet.getInt("revision"), resultSet.getString("declared_by"),
                    resultSet.getTimestamp("declared_at").toInstant(), resultSet.getString("updated_by"),
                    resultSet.getTimestamp("updated_at").toInstant());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("skill scope row is invalid", exception);
        }
    }

    private String maintainers(SkillScope scope) {
        try {
            return mapper.writeValueAsString(new ArrayList<>(scope.maintainerUserIds()));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("skill scope cannot be serialized", exception);
        }
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private SkillScopePersistenceException persistence(Throwable cause) {
        if (cause instanceof SkillScopePersistenceException exception) return exception;
        return new SkillScopePersistenceException("Skill scope state is unavailable", cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
