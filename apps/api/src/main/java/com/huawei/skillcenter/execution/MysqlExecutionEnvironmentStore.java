package com.huawei.skillcenter.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** MySQL JSON document persistence for the department execution-environment catalog. */
@Component
@Conditional(ExecutionEnvironmentBackendCondition.Mysql.class)
public class MysqlExecutionEnvironmentStore implements ExecutionEnvironmentRepository {
    private static final String KEY = "execution-environments";
    private static final int SCHEMA_VERSION = 1;
    private static final String SELECT = "select document_schema_version, revision, payload from department_json_documents where document_key = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public MysqlExecutionEnvironmentStore(JdbcTemplate jdbc, ObjectMapper mapper,
                                          PlatformTransactionManager transactionManager) {
        this(jdbc, mapper, transactionManager, Clock.systemUTC());
    }

    MysqlExecutionEnvironmentStore(JdbcTemplate jdbc, ObjectMapper mapper,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public List<ExecutionEnvironment> findAll(ExecutionEnvironmentKind kind, ExecutionEnvironmentStatus status) {
        return load().stream().filter(value -> kind == null || value.kind() == kind)
                .filter(value -> status == null || value.status() == status)
                .sorted(Comparator.comparing(ExecutionEnvironment::businessKey)).toList();
    }

    @Override
    public Optional<ExecutionEnvironment> find(ExecutionEnvironmentKind kind, String environmentId) {
        if (kind == null || environmentId == null || environmentId.isBlank()) return Optional.empty();
        return load().stream().filter(value -> value.kind() == kind && value.environmentId().equals(environmentId.trim())).findFirst();
    }

    @Override
    public ExecutionEnvironment create(ExecutionEnvironment value) {
        if (value == null) throw new IllegalArgumentException("execution environment must not be null");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<ExecutionEnvironment> values = current == null ? seed() : parse(current);
                if (values.stream().anyMatch(existing -> existing.businessKey().equals(value.businessKey()))) throw new IllegalArgumentException("execution environment already exists");
                values.add(value);
                write(values, current);
                return value;
            });
        } catch (ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException(exception);
        }
    }

    @Override
    public ExecutionEnvironment replace(ExecutionEnvironment value, int expectedRevision) {
        if (value == null) throw new IllegalArgumentException("execution environment must not be null");
        if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision must be at least 1");
        try {
            return transactions.execute(status -> {
                Row current = locked();
                List<ExecutionEnvironment> values = current == null ? seed() : parse(current);
                int index = -1;
                for (int i = 0; i < values.size(); i++) if (values.get(i).businessKey().equals(value.businessKey())) { index = i; break; }
                if (index < 0) throw new IllegalArgumentException("execution environment does not exist");
                ExecutionEnvironment existing = values.get(index);
                if (existing.revision() != expectedRevision || value.revision() != expectedRevision + 1) throw new ExecutionEnvironmentRevisionConflictException("execution environment revision conflict");
                values.set(index, value);
                write(values, current);
                return value;
            });
        } catch (ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException(exception);
        }
    }

    private List<ExecutionEnvironment> load() {
        try {
            List<Row> rows = jdbc.query(SELECT, this::row, KEY);
            if (rows.isEmpty()) return seed();
            if (rows.size() != 1) throw new IllegalArgumentException("execution environments aggregate is invalid");
            return parse(rows.getFirst());
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private Row locked() {
        List<Row> rows = jdbc.query(SELECT + " for update", this::row, KEY);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException("execution environments aggregate is invalid");
        return rows.getFirst();
    }

    private void write(List<ExecutionEnvironment> values, Row current) {
        long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
        String payload = serialize(values);
        if (current == null) {
            jdbc.update("insert into department_json_documents (document_key, document_schema_version, revision, payload) values (?, ?, ?, ?)", KEY, SCHEMA_VERSION, revision, payload);
            return;
        }
        int updated = jdbc.update("update department_json_documents set document_schema_version = ?, revision = ?, payload = ?, updated_at = current_timestamp where document_key = ? and revision = ?", SCHEMA_VERSION, revision, payload, KEY, current.revision());
        if (updated != 1) throw new ExecutionEnvironmentRevisionConflictException("execution environment aggregate revision conflict");
    }

    private List<ExecutionEnvironment> parse(Row row) {
        if (row.schemaVersion() != SCHEMA_VERSION || row.revision() < 0 || row.payload() == null) throw new IllegalArgumentException("execution environments aggregate is invalid");
        try {
            List<ExecutionEnvironment> loaded = mapper.readValue(row.payload(), new TypeReference<>() { });
            List<ExecutionEnvironment> values = List.copyOf(loaded == null ? List.of() : loaded);
            Set<String> keys = new HashSet<>();
            for (ExecutionEnvironment value : values) if (value == null || !keys.add(value.businessKey())) throw new IllegalArgumentException("duplicate execution environment business key");
            return values;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("execution environments aggregate is invalid", exception);
        }
    }

    private List<ExecutionEnvironment> seed() {
        Instant now = clock.instant();
        return new ArrayList<>(List.of(
                seed("openclaw", ExecutionEnvironmentKind.AGENT_RUNTIME, "openclaw-runner", now),
                seed("mcp-network", ExecutionEnvironmentKind.MCP_SERVER, "mcp-gateway", now),
                seed("llm-gateway", ExecutionEnvironmentKind.LLM_PROVIDER, "llm-gateway", now)));
    }

    private ExecutionEnvironment seed(String id, ExecutionEnvironmentKind kind, String provider, Instant now) {
        return new ExecutionEnvironment(id, kind, "context-v1", ExecutionEnvironmentStatus.ACTIVE,
                List.of("context-only"), provider, "", "system", now, "system", now);
    }

    private String serialize(List<ExecutionEnvironment> values) {
        try { return mapper.writeValueAsString(values); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("execution environments aggregate cannot be serialized", exception); }
    }

    private Row row(ResultSet rs, int ignored) throws SQLException { return new Row(rs.getInt("document_schema_version"), rs.getLong("revision"), rs.getString("payload")); }
    private ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException failure(Throwable cause) { return cause instanceof ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException value ? value : new ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException(cause); }
    private static <T> T require(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " is required"); return value; }
    private record Row(int schemaVersion, long revision, String payload) { }
}
