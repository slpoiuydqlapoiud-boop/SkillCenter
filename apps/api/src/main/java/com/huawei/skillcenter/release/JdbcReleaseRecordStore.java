package com.huawei.skillcenter.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL transaction implementation of the release record repository. */
@Component
@Conditional(ReleaseBackendCondition.Postgresql.class)
public class JdbcReleaseRecordStore implements ReleaseRecordRepository {
    private static final String COLUMNS = "release_id, skill_id, version, sha256, target_environment, "
            + "gate_snapshot::text AS gate_snapshot, source_assessment_id, rollback_of_release_id, "
            + "rollback_target_version, rollback_target_release_id, rollback_assessment_id, idempotency_key, "
            + "status, requested_by, requested_at, approved_by, approved_at, status_reason, target_reference, "
            + "started_at, completed_at, updated_by, updated_at";
    private static final String TABLE = "release_records";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcReleaseRecordStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbcTemplate, "jdbcTemplate");
        this.mapper = require(objectMapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<ReleaseRecord> findAll(String skillId, String version,
                                       ReleaseEnvironment environment, ReleaseStatus status) {
        StringBuilder sql = new StringBuilder("select ").append(COLUMNS).append(" from ").append(TABLE)
                .append(" where 1=1");
        List<Object> arguments = new ArrayList<>();
        if (skillId != null && !skillId.isBlank()) { sql.append(" and skill_id = ?"); arguments.add(skillId.trim()); }
        if (version != null && !version.isBlank()) { sql.append(" and version = ?"); arguments.add(version.trim()); }
        if (environment != null) { sql.append(" and target_environment = ?"); arguments.add(environment.name()); }
        if (status != null) { sql.append(" and status = ?"); arguments.add(status.name()); }
        sql.append(" order by updated_at desc, release_id");
        try {
            return jdbc.query(sql.toString(), this::map, arguments.toArray());
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public Optional<ReleaseRecord> find(String releaseId) {
        if (releaseId == null || releaseId.isBlank()) return Optional.empty();
        return queryOne("select " + COLUMNS + " from " + TABLE + " where release_id = ?", releaseId.trim());
    }

    @Override
    public Optional<ReleaseRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return queryOne("select " + COLUMNS + " from " + TABLE + " where idempotency_key = ?", idempotencyKey.trim());
    }

    @Override
    public Optional<ReleaseRecord> findActiveBusinessKey(String skillId, String version,
                                                         ReleaseEnvironment environment) {
        if (skillId == null || skillId.isBlank() || version == null || version.isBlank() || environment == null) {
            return Optional.empty();
        }
        return queryOne("select " + COLUMNS + " from " + TABLE
                + " where skill_id = ? and version = ? and target_environment = ? "
                + "and status not in ('PROMOTED', 'REJECTED', 'ROLLED_BACK', 'FAILED')",
                skillId.trim(), version.trim(), environment.name());
    }

    @Override
    public ReleaseRecord create(ReleaseRecord value) {
        require(value, "release");
        try {
            return transactions.execute(status -> {
                ensureNoDuplicate(value);
                try {
                    jdbc.update("insert into " + TABLE + " (" + COLUMNS.replace("gate_snapshot::text AS gate_snapshot", "gate_snapshot")
                                    + ") values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            parameters(value));
                    return value;
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw conflictAfterConstraint(value, exception);
                }
            });
        } catch (ReleaseConflictException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public ReleaseRecord replace(ReleaseRecord value) {
        require(value, "release");
        try {
            return transactions.execute(status -> {
                ReleaseRecord existing = locked(value.releaseId()).orElseThrow(
                        () -> new IllegalArgumentException("release does not exist"));
                ensureImmutableContext(existing, value);
                try {
                    int updated = jdbc.update("update " + TABLE + " set skill_id = ?, version = ?, sha256 = ?, "
                                    + "target_environment = ?, gate_snapshot = ?::jsonb, source_assessment_id = ?, "
                                    + "rollback_of_release_id = ?, rollback_target_version = ?, rollback_target_release_id = ?, "
                                    + "rollback_assessment_id = ?, idempotency_key = ?, status = ?, requested_by = ?, "
                                    + "requested_at = ?, approved_by = ?, approved_at = ?, status_reason = ?, "
                                    + "target_reference = ?, started_at = ?, completed_at = ?, updated_by = ?, updated_at = ? "
                                    + "where release_id = ?", updateParameters(value));
                    if (updated != 1) throw new IllegalStateException("release update was not applied");
                    return value;
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw conflictAfterConstraint(value, exception);
                }
            });
        } catch (ReleaseConflictException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private Optional<ReleaseRecord> queryOne(String sql, Object... arguments) {
        try {
            List<ReleaseRecord> values = jdbc.query(sql, this::map, arguments);
            if (values.size() > 1) throw new IllegalArgumentException("release query returned duplicate rows");
            return values.stream().findFirst();
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private Optional<ReleaseRecord> locked(String releaseId) {
        List<ReleaseRecord> values = jdbc.query("select " + COLUMNS + " from " + TABLE
                + " where release_id = ? for update", this::map, releaseId);
        if (values.size() > 1) throw new IllegalArgumentException("release query returned duplicate rows");
        return values.stream().findFirst();
    }

    private void ensureNoDuplicate(ReleaseRecord value) {
        if (find(value.releaseId()).isPresent()) throw new ReleaseConflictException("releaseId already exists");
        if (findByIdempotencyKey(value.idempotencyKey()).isPresent()) {
            throw new ReleaseConflictException("idempotencyKey already exists");
        }
        if (!value.status().terminal() && findActiveBusinessKey(value.skillId(), value.version(), value.targetEnvironment()).isPresent()) {
            throw new ReleaseConflictException("active release already exists for skill version and environment");
        }
    }

    private void ensureImmutableContext(ReleaseRecord existing, ReleaseRecord value) {
        if (!existing.skillId().equals(value.skillId()) || !existing.version().equals(value.version())
                || !existing.sha256().equals(value.sha256()) || existing.targetEnvironment() != value.targetEnvironment()
                || !existing.gateSnapshot().equals(value.gateSnapshot())
                || !existing.sourceAssessmentId().equals(value.sourceAssessmentId())
                || !existing.rollbackOfReleaseId().equals(value.rollbackOfReleaseId())
                || !existing.rollbackTargetVersion().equals(value.rollbackTargetVersion())
                || !existing.rollbackTargetReleaseId().equals(value.rollbackTargetReleaseId())
                || !existing.idempotencyKey().equals(value.idempotencyKey())
                || !existing.requestedBy().equals(value.requestedBy())
                || !existing.requestedAt().equals(value.requestedAt())) {
            throw new ReleaseConflictException("release context is immutable");
        }
    }

    private ReleaseConflictException conflictAfterConstraint(ReleaseRecord value, Throwable cause) {
        if (!value.status().terminal()) {
            return new ReleaseConflictException("active release already exists for skill version and environment");
        }
        return new ReleaseConflictException("release conflict");
    }

    private Object[] parameters(ReleaseRecord value) {
        return new Object[]{value.releaseId(), value.skillId(), value.version(), value.sha256(), value.targetEnvironment().name(),
                gateJson(value.gateSnapshot()), value.sourceAssessmentId(), value.rollbackOfReleaseId(), value.rollbackTargetVersion(),
                value.rollbackTargetReleaseId(), value.rollbackAssessmentId(), value.idempotencyKey(), value.status().name(),
                value.requestedBy(), Timestamp.from(value.requestedAt()), value.approvedBy(), timestamp(value.approvedAt()),
                value.statusReason(), value.targetReference(), timestamp(value.startedAt()), timestamp(value.completedAt()),
                value.updatedBy(), Timestamp.from(value.updatedAt())};
    }

    private Object[] updateParameters(ReleaseRecord value) {
        Object[] parameters = parameters(value);
        Object[] update = new Object[parameters.length];
        System.arraycopy(parameters, 1, update, 0, parameters.length - 1);
        update[parameters.length - 1] = value.releaseId();
        return update;
    }

    private String gateJson(ReleaseGateSnapshot snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("release gate snapshot cannot be serialized", exception);
        }
    }

    private ReleaseRecord map(ResultSet rs, int ignored) throws SQLException {
        try {
            return new ReleaseRecord(rs.getString("release_id"), rs.getString("skill_id"), rs.getString("version"),
                    rs.getString("sha256"), ReleaseEnvironment.from(rs.getString("target_environment")),
                    mapper.readValue(rs.getString("gate_snapshot"), ReleaseGateSnapshot.class),
                    rs.getString("source_assessment_id"), rs.getString("rollback_of_release_id"),
                    rs.getString("rollback_target_version"), rs.getString("rollback_target_release_id"),
                    rs.getString("rollback_assessment_id"), rs.getString("idempotency_key"),
                    ReleaseStatus.valueOf(rs.getString("status")), rs.getString("requested_by"), instant(rs, "requested_at"),
                    rs.getString("approved_by"), instant(rs, "approved_at"), rs.getString("status_reason"),
                    rs.getString("target_reference"), instant(rs, "started_at"), instant(rs, "completed_at"),
                    rs.getString("updated_by"), instant(rs, "updated_at"));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("release record is invalid", exception);
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private ReleasePersistenceException failure(Throwable cause) {
        if (cause instanceof ReleasePersistenceException persistence) return persistence;
        return new ReleasePersistenceException(cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
