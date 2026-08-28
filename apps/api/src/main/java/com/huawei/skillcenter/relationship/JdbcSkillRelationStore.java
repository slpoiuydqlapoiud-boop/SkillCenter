package com.huawei.skillcenter.relationship;

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

/** PostgreSQL persistence for shared version relationships. */
@Component
@Conditional(SkillRelationBackendCondition.Postgresql.class)
public class JdbcSkillRelationStore implements SkillRelationRepository {
    private static final String TABLE = "skill_relations";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcSkillRelationStore(JdbcTemplate jdbc, ObjectMapper ignoredMapper,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        require(ignoredMapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public Optional<SkillRelation> find(String relationId) {
        if (relationId == null || relationId.isBlank()) return Optional.empty();
        try {
            List<SkillRelation> values = jdbc.query("select * from " + TABLE + " where relation_id = ?",
                    this::map, relationId.trim());
            return values.stream().findFirst();
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public List<SkillRelation> findAll(String sourceSkillId, String sourceVersion,
                                       String targetSkillId, String targetVersion,
                                       SkillRelationStatus status) {
        StringBuilder sql = new StringBuilder("select * from ").append(TABLE).append(" where 1=1");
        List<Object> args = new ArrayList<>();
        append(sql, args, "source_skill_id", sourceSkillId);
        append(sql, args, "source_version", sourceVersion);
        append(sql, args, "target_skill_id", targetSkillId);
        append(sql, args, "target_version", targetVersion);
        if (status != null) {
            sql.append(" and status = ?");
            args.add(status.name());
        }
        sql.append(" order by declared_at, relation_id");
        try {
            return jdbc.query(sql.toString(), this::map, args.toArray());
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public SkillRelation create(SkillRelation relation) {
        require(relation, "relation");
        try {
            transactions.execute(status -> {
                try {
                    lockRelations();
                    insert(relation);
                    return null;
                } catch (DuplicateKeyException exception) {
                    status.setRollbackOnly();
                    throw new SkillRelationConflictException("active relation already exists");
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw persistence(exception);
                }
            });
            return relation;
        } catch (SkillRelationConflictException | SkillRelationPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public SkillRelation replace(SkillRelation relation) {
        require(relation, "relation");
        try {
            transactions.execute(status -> {
                try {
                    lockRelations();
                    SkillRelation existing = find(relation.relationId()).orElseThrow(
                            () -> new SkillRelationConflictException("relation does not exist"));
                    ensureImmutableContext(existing, relation);
                    int count = jdbc.update("update " + TABLE + " set status = ?, retired_by = ?, retired_at = ?,"
                                    + " status_reason = ? where relation_id = ?",
                            relation.status().name(), nullable(relation.retiredBy()),
                            relation.retiredAt() == null ? null : Timestamp.from(relation.retiredAt()),
                            nullable(relation.statusReason()), relation.relationId());
                    if (count != 1) {
                        status.setRollbackOnly();
                        throw new SkillRelationConflictException("relation does not exist");
                    }
                    return null;
                } catch (SkillRelationConflictException exception) {
                    status.setRollbackOnly();
                    throw exception;
                } catch (DuplicateKeyException exception) {
                    status.setRollbackOnly();
                    throw new SkillRelationConflictException("active relation already exists");
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw persistence(exception);
                }
            });
            return relation;
        } catch (SkillRelationConflictException | SkillRelationPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private void lockRelations() {
        jdbc.execute("select pg_advisory_xact_lock(hashtext('skill-center:skill-relations'))");
    }

    private void insert(SkillRelation relation) {
        jdbc.update("insert into " + TABLE + " (relation_id, source_skill_id, source_version, target_skill_id,"
                        + " target_version, relation_type, status, declared_by, declared_at, retired_by, retired_at,"
                        + " status_reason) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                relation.relationId(), relation.sourceSkillId(), relation.sourceVersion(), relation.targetSkillId(),
                relation.targetVersion(), relation.relationType().name(), relation.status().name(), relation.declaredBy(),
                Timestamp.from(relation.declaredAt()), nullable(relation.retiredBy()),
                relation.retiredAt() == null ? null : Timestamp.from(relation.retiredAt()),
                nullable(relation.statusReason()));
    }

    private SkillRelation map(ResultSet resultSet, int ignored) throws SQLException {
        return new SkillRelation(resultSet.getString("relation_id"), resultSet.getString("source_skill_id"),
                resultSet.getString("source_version"), resultSet.getString("target_skill_id"),
                resultSet.getString("target_version"),
                SkillRelationType.valueOf(resultSet.getString("relation_type")),
                SkillRelationStatus.valueOf(resultSet.getString("status")), resultSet.getString("declared_by"),
                resultSet.getTimestamp("declared_at").toInstant(), resultSet.getString("retired_by"),
                resultSet.getTimestamp("retired_at") == null ? null : resultSet.getTimestamp("retired_at").toInstant(),
                resultSet.getString("status_reason"));
    }

    private void ensureImmutableContext(SkillRelation existing, SkillRelation relation) {
        if (!existing.sourceSkillId().equals(relation.sourceSkillId())
                || !existing.sourceVersion().equals(relation.sourceVersion())
                || !existing.targetSkillId().equals(relation.targetSkillId())
                || !existing.targetVersion().equals(relation.targetVersion())
                || existing.relationType() != relation.relationType()
                || !existing.declaredBy().equals(relation.declaredBy())
                || !existing.declaredAt().equals(relation.declaredAt())) {
            throw new SkillRelationConflictException("relation context is immutable");
        }
    }

    private static void append(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" and ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private SkillRelationPersistenceException persistence(Throwable cause) {
        if (cause instanceof SkillRelationPersistenceException exception) return exception;
        return new SkillRelationPersistenceException(cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
