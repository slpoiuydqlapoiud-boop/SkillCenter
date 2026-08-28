package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** PostgreSQL JSONB implementation of the quality evidence aggregate repository. */
@Component
@Conditional(QualityEvidenceBackendCondition.Postgresql.class)
public class JdbcQualityEvidenceStore implements QualityEvidenceRepository {
    private static final String KEY = "quality-evidence";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload::text from skill_quality_evidence_state where aggregate_key = ?";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcQualityEvidenceStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                    PlatformTransactionManager transactionManager) {
        this.jdbc = jdbcTemplate;
        this.mapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public QualityEvidenceState load() {
        try {
            List<Row> rows = read(SELECT);
            if (rows.isEmpty()) return empty();
            if (rows.size() != 1) throw new IllegalArgumentException("quality evidence aggregate is invalid");
            return parse(rows.getFirst());
        } catch (RuntimeException exception) { throw failure(exception); }
    }

    @Override
    public void save(QualityEvidenceState state) {
        try {
            validate(state);
            transaction(() -> { Row current = locked(); write(state, current); return null; });
        }
        catch (RuntimeException exception) { throw failure(exception); }
    }

    @Override
    public QualityEvidenceState update(StateUpdate update) {
        if (update == null) throw new IllegalArgumentException("quality evidence update is required");
        try {
            return transaction(() -> {
                Row current = locked();
                QualityEvidenceState next = update.apply(current == null ? empty() : parse(current));
                validate(next); write(next, current); return next;
            });
        } catch (RuntimeException exception) { throw failure(exception); }
    }

    @Override public void clear() { save(empty()); }

    private List<Row> read(String sql) {
        return jdbc.query(sql, (rs, ignored) -> new Row(rs.getInt("document_schema_version"),
                rs.getLong("revision"), rs.getString("payload")), KEY);
    }
    private Row locked() {
        List<Row> rows = read(SELECT + " for update");
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException("quality evidence aggregate is invalid");
        return rows.getFirst();
    }
    private void write(QualityEvidenceState state, Row current) {
        long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
        String payload = serialize(state);
        if (current == null) {
            jdbc.update("insert into skill_quality_evidence_state (aggregate_key, document_schema_version, revision, payload, updated_at) values (?, ?, ?, ?::jsonb, current_timestamp)", KEY, SCHEMA_VERSION, revision, payload);
            return;
        }
        int updated = jdbc.update("update skill_quality_evidence_state set document_schema_version = ?, revision = ?, payload = ?::jsonb, updated_at = current_timestamp where aggregate_key = ? and revision = ?", SCHEMA_VERSION, revision, payload, KEY, current.revision());
        if (updated != 1) throw new IllegalStateException("quality evidence revision conflict");
    }
    private QualityEvidenceState parse(Row row) {
        if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) throw new IllegalArgumentException("quality evidence aggregate is invalid");
        try {
            QualityEvidenceState state = mapper.readValue(row.payload(), QualityEvidenceState.class);
            if (state == null) throw new IllegalArgumentException("quality evidence aggregate is invalid");
            QualityEvidenceStateValidator.validate(state); return state;
        } catch (JsonProcessingException | IllegalArgumentException exception) { throw new IllegalArgumentException("quality evidence aggregate is invalid", exception); }
    }
    private String serialize(QualityEvidenceState state) {
        try { return mapper.writeValueAsString(state); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("quality evidence aggregate cannot be serialized", exception); }
    }
    private void validate(QualityEvidenceState state) {
        if (state == null) throw new IllegalArgumentException("quality evidence state is required");
        QualityEvidenceStateValidator.validate(state);
    }
    private <T> T transaction(java.util.concurrent.Callable<T> operation) {
        return transactions.execute(status -> {
            try { return operation.call(); }
            catch (RuntimeException exception) { status.setRollbackOnly(); throw exception; }
            catch (Exception exception) { status.setRollbackOnly(); throw new IllegalStateException("quality evidence transaction failed", exception); }
        });
    }
    private QualityEvidenceState empty() {
        QualityEvidenceState empty = new QualityEvidenceState(List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of());
        QualityEvidenceStateValidator.validate(empty);
        return empty;
    }
    private QualityEvidenceStore.QualityEvidencePersistenceException failure(Throwable cause) { return new QualityEvidenceStore.QualityEvidencePersistenceException(cause); }
    private record Row(int schemaVersion, long revision, String payload) { }
}
