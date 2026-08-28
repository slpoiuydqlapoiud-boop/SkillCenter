package com.huawei.skillcenter.operations;

import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL implementation for safe production handoff evidence metadata. */
@Component
@Conditional(ProductionEvidenceBackendCondition.Postgresql.class)
public class JdbcProductionEvidenceStore implements ProductionEvidenceRepository {
    private static final String TABLE = "skill_production_evidence";
    private static final String COLUMNS = "evidence_id, status, owner_user_id, verified_at, expires_at, "
            + "evidence_ref, summary, revision, updated_by, updated_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcProductionEvidenceStore(JdbcTemplate jdbcTemplate,
                                       PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbcTemplate, "jdbcTemplate");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public List<ProductionEvidence> findAll() {
        try {
            return jdbc.query("select " + COLUMNS + " from " + TABLE + " order by evidence_id", this::map);
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public Optional<ProductionEvidence> find(String evidenceId) {
        if (evidenceId == null || evidenceId.isBlank()) return Optional.empty();
        String normalized = ProductionEvidenceCatalog.requireId(evidenceId);
        try {
            return queryOne(normalized);
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public ProductionEvidence upsert(ProductionEvidence evidence, int expectedRevision) {
        if (evidence == null) throw new IllegalArgumentException("production evidence is required");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        try {
            return transactions.execute(status -> {
                Optional<ProductionEvidence> existing = locked(evidence.evidenceId());
                int currentRevision = existing.map(ProductionEvidence::revision).orElse(0);
                if (currentRevision != expectedRevision) {
                    throw new ProductionEvidenceConflictException("production evidence revision conflict");
                }
                if (evidence.revision() != expectedRevision + 1) {
                    throw new ProductionEvidenceConflictException("production evidence revision must advance by one");
                }
                try {
                    if (existing.isEmpty()) {
                        jdbc.update("insert into " + TABLE + " (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                parameters(evidence));
                    } else {
                        int updated = jdbc.update("update " + TABLE + " set status = ?, owner_user_id = ?, "
                                        + "verified_at = ?, expires_at = ?, evidence_ref = ?, summary = ?, revision = ?, "
                                        + "updated_by = ?, updated_at = ? where evidence_id = ? and revision = ?",
                                updateParameters(evidence, expectedRevision));
                        if (updated != 1) {
                            throw new ProductionEvidenceConflictException("production evidence revision conflict");
                        }
                    }
                    return evidence;
                } catch (DataAccessException exception) {
                    status.setRollbackOnly();
                    throw failure(exception);
                }
            });
        } catch (ProductionEvidenceConflictException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private Optional<ProductionEvidence> queryOne(String evidenceId) {
        List<ProductionEvidence> values = jdbc.query("select " + COLUMNS + " from " + TABLE
                + " where evidence_id = ?", this::map, evidenceId);
        if (values.size() > 1) throw new IllegalArgumentException("production evidence query returned duplicate rows");
        return values.stream().findFirst();
    }

    private Optional<ProductionEvidence> locked(String evidenceId) {
        List<ProductionEvidence> values = jdbc.query("select " + COLUMNS + " from " + TABLE
                + " where evidence_id = ? for update", this::map, evidenceId);
        if (values.size() > 1) throw new IllegalArgumentException("production evidence query returned duplicate rows");
        return values.stream().findFirst();
    }

    private Object[] parameters(ProductionEvidence value) {
        return new Object[]{value.evidenceId(), value.status(), value.ownerUserId(), timestamp(value.verifiedAt()),
                timestamp(value.expiresAt()), value.evidenceRef(), value.summary(), value.revision(),
                value.updatedBy(), timestamp(value.updatedAt())};
    }

    private Object[] updateParameters(ProductionEvidence value, int expectedRevision) {
        return new Object[]{value.status(), value.ownerUserId(), timestamp(value.verifiedAt()), timestamp(value.expiresAt()),
                value.evidenceRef(), value.summary(), value.revision(), value.updatedBy(), timestamp(value.updatedAt()),
                value.evidenceId(), expectedRevision};
    }

    private ProductionEvidence map(ResultSet resultSet, int ignored) throws SQLException {
        try {
            return new ProductionEvidence(resultSet.getString("evidence_id"), resultSet.getString("status"),
                    resultSet.getString("owner_user_id"), instant(resultSet, "verified_at"),
                    instant(resultSet, "expires_at"), resultSet.getString("evidence_ref"),
                    resultSet.getString("summary"), resultSet.getInt("revision"),
                    resultSet.getString("updated_by"), instant(resultSet, "updated_at"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("production evidence is invalid", exception);
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private ProductionEvidencePersistenceException failure(Throwable cause) {
        if (cause instanceof ProductionEvidencePersistenceException persistence) return persistence;
        return new ProductionEvidencePersistenceException("Unable to access production evidence state", cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
