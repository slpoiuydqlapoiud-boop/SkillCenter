package com.huawei.skillcenter.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** PostgreSQL-backed shared search projection; JSON remains the local default. */
public final class JdbcSkillSearchIndex implements SkillSearchIndex {
    private static final String STATE_TABLE = "skill_search_index_state";
    private static final String DOCUMENT_TABLE = "skill_search_documents";
    private static final int MAX_CANDIDATES = 5_000;
    private static final String LOCK_KEY = "skillcenter:skill-search-index";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcSkillSearchIndex(JdbcTemplate jdbc, ObjectMapper mapper,
                                PlatformTransactionManager transactionManager) {
        this.jdbc = require(jdbc, "jdbcTemplate");
        this.mapper = require(mapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    }

    @Override
    public String backend() {
        return "postgresql";
    }

    @Override
    public SkillSearchIndexStatus status() {
        try {
            SearchState state = readState();
            if (state == null) {
                return new SkillSearchIndexStatus("NOT_READY", 0, 0, "", "0", null, "SEARCH_INDEX_NOT_INITIALIZED");
            }
            Integer count = jdbc.queryForObject("select count(*) from " + DOCUMENT_TABLE, Integer.class);
            return new SkillSearchIndexStatus(state.state(), state.revision(), count == null ? 0 : count,
                    state.sourceHash(), Integer.toString(state.revision()), state.indexedAt(), state.reasonCode());
        } catch (RuntimeException exception) {
            return new SkillSearchIndexStatus("NOT_READY", 0, 0, "", "0", null,
                    "SEARCH_INDEX_PERSISTENCE_UNAVAILABLE");
        }
    }

    @Override
    public SkillSearchRebuildResult rebuild(List<SkillSearchDocument> documents, String sourceHash) {
        String validatedHash = SkillSearchDocument.boundedRequired(sourceHash, "sourceHash", 256);
        List<SkillSearchDocument> validatedDocuments = validateDocuments(documents);
        try {
            SkillSearchRebuildResult result = transactions.execute(status -> {
                try {
                    jdbc.update("select pg_advisory_xact_lock(hashtext(?))", LOCK_KEY);
                    SearchState current = readState();
                    int currentCount = countDocuments();
                    if (current != null && "READY".equals(current.state())
                            && validatedHash.equals(current.sourceHash())
                            && currentCount == validatedDocuments.size()) {
                        return result(current, currentCount);
                    }

                    jdbc.update("delete from " + DOCUMENT_TABLE);
                    for (SkillSearchDocument document : validatedDocuments) {
                        insert(document);
                    }
                    int revision = current == null ? 1 : current.revision() + 1;
                    Instant indexedAt = Instant.now();
                    jdbc.update("insert into " + STATE_TABLE
                                    + " (index_id, state, revision, source_hash, indexed_at, reason_code)"
                                    + " values ('default', 'READY', ?, ?, ?, '')"
                                    + " on conflict (index_id) do update set state = excluded.state,"
                                    + " revision = excluded.revision, source_hash = excluded.source_hash,"
                                    + " indexed_at = excluded.indexed_at, reason_code = excluded.reason_code",
                            revision, validatedHash, Timestamp.from(indexedAt));
                    return new SkillSearchRebuildResult(revision, validatedDocuments.size(), validatedHash,
                            Integer.toString(revision));
                } catch (RuntimeException exception) {
                    status.setRollbackOnly();
                    throw persistence(exception);
                }
            });
            if (result == null) {
                throw new IllegalStateException("search index transaction returned no result");
            }
            return result;
        } catch (SkillSearchIndexPersistenceException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public void invalidate(String reason) {
        SkillSearchDocument.bounded(reason, "reason", 256);
        try {
            transactions.execute(status -> {
                try {
                    int updated = jdbc.update("update " + STATE_TABLE
                                    + " set state = 'STALE', reason_code = ? where index_id = 'default'",
                            reason == null ? "" : reason);
                    if (updated == 0) {
                        jdbc.update("insert into " + STATE_TABLE
                                        + " (index_id, state, revision, source_hash, indexed_at, reason_code)"
                                        + " values ('default', 'STALE', 0, '', NULL, ?)",
                                reason == null ? "" : reason);
                    }
                    return null;
                } catch (RuntimeException exception) {
                    status.setRollbackOnly();
                    throw persistence(exception);
                }
            });
        } catch (SkillSearchIndexPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    @Override
    public List<SkillSearchHit> search(SkillSearchQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query is required");
        }
        try {
            SearchState state = readState();
            if (state == null || !"READY".equals(state.state())) {
                return List.of();
            }
            StringBuilder sql = new StringBuilder("select * from ").append(DOCUMENT_TABLE).append(" where 1 = 1");
            List<Object> arguments = new ArrayList<>();
            if (!query.text().isBlank()) {
                sql.append(" and to_tsvector('simple', search_tokens) @@ plainto_tsquery('simple', ?)");
                arguments.add(query.text());
            }
            if (!query.category().isBlank()) {
                sql.append(" and lower(category) = lower(?)");
                arguments.add(query.category());
            }
            if (!query.status().isBlank()) {
                sql.append(" and status = ?");
                arguments.add(query.status());
            }
            if (!query.risk().isBlank()) {
                sql.append(" and risk = ?");
                arguments.add(query.risk());
            }
            sql.append(" order by skill_id limit ").append(MAX_CANDIDATES);
            List<SkillSearchDocument> documents = jdbc.query(sql.toString(), this::mapDocument,
                    arguments.toArray());
            JsonSkillSearchIndex matcher = new JsonSkillSearchIndex();
            matcher.rebuild(documents, state.sourceHash().isBlank() ? "postgresql" : state.sourceHash());
            return matcher.search(query);
        } catch (RuntimeException exception) {
            throw persistence(exception);
        }
    }

    private SearchState readState() {
        List<SearchState> states = jdbc.query("select state, revision, source_hash, indexed_at, reason_code from "
                        + STATE_TABLE + " where index_id = 'default'",
                this::mapState);
        return states.isEmpty() ? null : states.getFirst();
    }

    private int countDocuments() {
        Integer count = jdbc.queryForObject("select count(*) from " + DOCUMENT_TABLE, Integer.class);
        return count == null ? 0 : count;
    }

    private void insert(SkillSearchDocument document) {
        jdbc.update("insert into " + DOCUMENT_TABLE
                        + " (skill_id, name, description, tags, team, category, status, risk, last_updated,"
                        + " published_at, latest_version, visibility, owner_team_id, search_tokens)"
                        + " values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                document.skillId(), document.name(), document.description(), tags(document), document.team(),
                document.category(), document.status(), document.risk(), timestamp(document.lastUpdated()),
                timestamp(document.publishedAt()), document.latestVersion(), document.visibility(),
                document.ownerTeamId(), searchTokens(document));
    }

    private SkillSearchDocument mapDocument(ResultSet resultSet, int ignored) throws SQLException {
        try {
            List<String> tags = mapper.readValue(resultSet.getString("tags"), mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            return new SkillSearchDocument(resultSet.getString("skill_id"), resultSet.getString("name"),
                    resultSet.getString("description"), tags, resultSet.getString("team"),
                    resultSet.getString("category"), resultSet.getString("status"), resultSet.getString("risk"),
                    instant(resultSet, "last_updated"), instant(resultSet, "published_at"),
                    resultSet.getString("latest_version"), resultSet.getString("visibility"),
                    resultSet.getString("owner_team_id"));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("search document row is invalid", exception);
        }
    }

    private SearchState mapState(ResultSet resultSet, int ignored) throws SQLException {
        return new SearchState(resultSet.getString("state"), resultSet.getInt("revision"),
                resultSet.getString("source_hash"), instant(resultSet, "indexed_at"),
                resultSet.getString("reason_code"));
    }

    private String tags(SkillSearchDocument document) {
        try {
            return mapper.writeValueAsString(document.tags());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("search document tags cannot be serialized", exception);
        }
    }

    private String searchTokens(SkillSearchDocument document) {
        List<String> values = new ArrayList<>();
        values.add(document.skillId());
        values.add(document.name());
        values.addAll(document.tags());
        values.add(document.description());
        values.add(document.team());
        values.add(document.category());
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT)).reduce((left, right) -> left + " " + right).orElse("");
    }

    private List<SkillSearchDocument> validateDocuments(List<SkillSearchDocument> documents) {
        if (documents == null) {
            throw new IllegalArgumentException("documents is required");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (SkillSearchDocument document : documents) {
            if (document == null) {
                throw new IllegalArgumentException("documents must not contain null");
            }
            if (!ids.add(document.skillId())) {
                throw new IllegalArgumentException("documents must not contain duplicate skillId values");
            }
        }
        return List.copyOf(documents);
    }

    private SkillSearchRebuildResult result(SearchState state, int documentCount) {
        return new SkillSearchRebuildResult(state.revision(), documentCount, state.sourceHash(),
                Integer.toString(state.revision()));
    }

    private SkillSearchIndexPersistenceException persistence(Throwable cause) {
        if (cause instanceof SkillSearchIndexPersistenceException exception) return exception;
        return new SkillSearchIndexPersistenceException(cause);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record SearchState(String state, int revision, String sourceHash, Instant indexedAt, String reasonCode) {
    }
}
