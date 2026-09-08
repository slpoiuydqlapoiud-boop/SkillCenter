package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.search.SkillSearchRefreshEvent;
import com.huawei.skillcenter.search.SkillSearchRefreshEventStore;
import org.springframework.context.annotation.Conditional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** PostgreSQL transaction implementation for the governance aggregate. */
@Component
@Conditional(GovernanceBackendCondition.Postgresql.class)
public class JdbcGovernanceStateRepository implements GovernanceStateRepository {
    private static final String STATE_KEY = "governance-state";
    private static final String POLICY_KEY = "platform-policy";
    private static final String VERSION_COLUMNS = "package_id, skill_id, version, status, sha256, size_bytes, "
            + "artifact_path, uploaded_by, uploaded_at, published_by, published_at, review_id, status_reason, "
            + "replacement_version, status_changed_by, status_changed_at, risk_level, "
            + "security_evidence::text AS security_evidence";
    private static final String REVIEW_COLUMNS = "review_id, package_id, skill_id, version, status, submitted_by, "
            + "submitted_at, reviewed_by, reviewed_at, reason, risk_level, security_reviewed_by, "
            + "security_reviewed_at, security_reason, security_evidence::text AS security_evidence";
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final ObjectProvider<SkillSearchRefreshEventStore> refreshEventStoreProvider;

    public JdbcGovernanceStateRepository(JdbcTemplate jdbcTemplate,
                                         ObjectMapper objectMapper,
                                         PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, objectMapper, transactionManager, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcGovernanceStateRepository(JdbcTemplate jdbcTemplate,
                                         ObjectMapper objectMapper,
                                         PlatformTransactionManager transactionManager,
                                         ObjectProvider<SkillSearchRefreshEventStore> refreshEventStoreProvider) {
        this.jdbc = require(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = require(objectMapper, "objectMapper");
        this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
        this.refreshEventStoreProvider = refreshEventStoreProvider;
    }

    @Override
    public Optional<GovernanceState> load() {
        try {
            return transactions.execute(status -> readConsistent());
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public GovernanceState loadOrSeed(Supplier<GovernanceSnapshot> seed) {
        if (seed == null) throw new IllegalArgumentException("seed must not be null");
        try {
            return transactions.execute(status -> {
                Optional<GovernanceState> existing = locked();
                if (existing.isPresent()) {
                    GovernanceState current = withRelationalFacts(existing.get());
                    ensureVersionRows(current.snapshot().versions());
                    ensureReviewRows(current.snapshot().reviews());
                    ensureConfigurationRows(current.snapshot().configuration());
                    return current;
                }
                GovernanceSnapshot snapshot = seed.get();
                if (snapshot == null) throw new IllegalArgumentException("seed snapshot must not be null");
                jdbc.update("insert into skill_governance_state "
                                + "(state_key, state, revision, updated_at) values (?, ?::jsonb, ?, current_timestamp)",
                        STATE_KEY, json(snapshot), 0L);
                ensureVersionRows(snapshot.versions());
                ensureReviewRows(snapshot.reviews());
                ensureConfigurationRows(snapshot.configuration());
                return new GovernanceState(0, snapshot);
            });
        } catch (GovernanceStateConflictException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public GovernanceState replace(long expectedRevision, GovernanceSnapshot snapshot) {
        return replace(expectedRevision, snapshot, List.of());
    }

    @Override
    public GovernanceState replace(long expectedRevision, GovernanceSnapshot snapshot,
                                   List<SkillSearchRefreshEvent> refreshEvents) {
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        try {
            return transactions.execute(status -> {
                GovernanceState current = locked().orElseThrow(
                        () -> new GovernanceStore.GovernancePersistenceException(
                                new IllegalStateException("governance state row is missing")));
                if (current.revision() != expectedRevision) {
                    throw new GovernanceStateConflictException("governance state revision conflict");
                }
                long nextRevision = Math.addExact(expectedRevision, 1L);
                ensureVersionRows(snapshot.versions());
                ensureReviewRows(snapshot.reviews());
                ensureConfigurationRows(snapshot.configuration());
                int updated = jdbc.update("update skill_governance_state "
                                + "set state = ?::jsonb, revision = ?, updated_at = current_timestamp "
                                + "where state_key = ? and revision = ?",
                        json(snapshot), nextRevision, STATE_KEY, expectedRevision);
                if (updated != 1) throw new GovernanceStateConflictException("governance state revision conflict");
                appendRefreshEvents(refreshEvents);
                return new GovernanceState(nextRevision, snapshot);
            });
        } catch (GovernanceStateConflictException | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

    private void appendRefreshEvents(List<SkillSearchRefreshEvent> refreshEvents) {
        if (refreshEvents == null || refreshEvents.isEmpty()) return;
        SkillSearchRefreshEventStore store = refreshEventStoreProvider == null
                ? null : refreshEventStoreProvider.getIfAvailable();
        if (store == null) return;
        for (SkillSearchRefreshEvent event : refreshEvents) {
            if (event == null) throw new IllegalArgumentException("refresh event is required");
            store.append(event);
        }
    }

    private Optional<GovernanceState> locked() {
        List<GovernanceState> rows = jdbc.query(
                "select revision, state::text as state from skill_governance_state where state_key = ? for update",
                this::map, STATE_KEY);
        if (rows.size() > 1) throw new IllegalStateException("governance state returned duplicate rows");
        return rows.stream().findFirst();
    }

    private Optional<GovernanceState> readConsistent() {
        Optional<GovernanceState> current = lockedShare();
        return current.map(this::withRelationalFacts);
    }

    private Optional<GovernanceState> lockedShare() {
        List<GovernanceState> rows = jdbc.query(
                "select revision, state::text as state from skill_governance_state where state_key = ? for share",
                this::map, STATE_KEY);
        if (rows.size() > 1) throw new IllegalStateException("governance state returned duplicate rows");
        return rows.stream().findFirst();
    }

    private GovernanceState withRelationalFacts(GovernanceState aggregate) {
        List<SkillVersion> versions = jdbc.query("select " + VERSION_COLUMNS
                        + " from skill_governance_version order by skill_id, version",
                this::mapVersion);
        List<ReviewTask> reviews = jdbc.query("select " + REVIEW_COLUMNS
                        + " from skill_governance_review order by submitted_at nulls last, review_id",
                this::mapReview);
        GovernanceSnapshot snapshot = aggregate.snapshot();
        List<SkillVersion> resolvedVersions = versions.isEmpty() && !snapshot.versions().isEmpty()
                ? snapshot.versions() : versions;
        List<ReviewTask> resolvedReviews = reviews.isEmpty() && !snapshot.reviews().isEmpty()
                ? snapshot.reviews() : reviews;
        GovernanceConfiguration resolvedConfiguration = withRelationalConfiguration(snapshot.configuration());
        if (resolvedVersions.equals(snapshot.versions()) && resolvedReviews.equals(snapshot.reviews())
                && resolvedConfiguration.equals(snapshot.configuration())) {
            return aggregate;
        }
        GovernanceSnapshot merged = new GovernanceSnapshot(resolvedVersions, resolvedReviews, snapshot.installations(),
                snapshot.audits(), snapshot.authorizations(), snapshot.favorites(), resolvedConfiguration,
                snapshot.invocationEvents(), snapshot.exportJobs(), snapshot.retentionPolicy(),
                snapshot.auditIntegrity(), snapshot.notifications());
        return new GovernanceState(aggregate.revision(), merged);
    }

    private void ensureVersionRows(List<SkillVersion> versions) {
        jdbc.update("delete from skill_governance_version");
        for (SkillVersion version : versions == null ? List.<SkillVersion>of() : versions) {
            jdbc.update("insert into skill_governance_version (" + VERSION_COLUMNS
                            .replace("security_evidence::text AS security_evidence", "security_evidence")
                            + ", updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, current_timestamp)",
                    version.packageId(), version.skillId(), version.version(), version.status(), version.sha256(),
                    version.sizeBytes(), version.artifactPath(), version.uploadedBy(), timestamp(version.uploadedAt()),
                    version.publishedBy(), timestamp(version.publishedAt()), version.reviewId(),
                    safe(version.statusReason()), safe(version.replacementVersion()), safe(version.statusChangedBy()),
                    timestamp(version.statusChangedAt()), version.riskLevel(), securityJson(version.securityEvidence()));
        }
    }

    private void ensureReviewRows(List<ReviewTask> reviews) {
        jdbc.update("delete from skill_governance_review");
        for (ReviewTask review : reviews == null ? List.<ReviewTask>of() : reviews) {
            jdbc.update("insert into skill_governance_review (" + REVIEW_COLUMNS
                            .replace("security_evidence::text AS security_evidence", "security_evidence")
                            + ", updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, current_timestamp)",
                    review.reviewId(), review.packageId(), review.skillId(), review.version(), review.status(),
                    review.submittedBy(), timestamp(review.submittedAt()), review.reviewedBy(), timestamp(review.reviewedAt()),
                    review.reason(), safe(review.riskLevel()), review.securityReviewedBy(),
                    timestamp(review.securityReviewedAt()), review.securityReason(), securityJson(review.securityEvidence()));
        }
    }

    private GovernanceConfiguration withRelationalConfiguration(GovernanceConfiguration legacy) {
        List<TeamDefinition> teams = jdbc.query("select team_id, name, description, owner_user_id, "
                        + "member_user_ids::text as member_user_ids, status, created_at, updated_at "
                        + "from skill_governance_team order by team_id", this::mapTeam);
        List<RoleBinding> roleBindings = jdbc.query("select user_id, role, team_id, status, changed_by, changed_at "
                        + "from skill_governance_role_binding order by user_id", this::mapRoleBinding);
        List<CategoryDefinition> categories = jdbc.query("select code, display_name, description, sort_order, status, "
                        + "updated_by, updated_at from skill_governance_category order by sort_order, code",
                this::mapCategory);
        List<TagDefinition> tags = jdbc.query("select code, display_name, description, sort_order, status, "
                        + "updated_by, updated_at from skill_governance_tag order by sort_order, code", this::mapTag);
        List<CollectionDefinition> collections = jdbc.query("select collection_id, name, description, owner_team_id, "
                        + "visibility, skill_ids::text as skill_ids, sort_order, status, updated_by, updated_at "
                        + "from skill_governance_collection order by sort_order, collection_id", this::mapCollection);
        Optional<PlatformPolicy> policy = jdbc.query("select policy_version, page_size_options::text as page_size_options, "
                        + "max_page_size, minimum_client_version, default_collection_visibility, updated_by, updated_at "
                        + "from skill_governance_policy where policy_key = ?", this::mapPolicy, POLICY_KEY)
                .stream().findFirst();

        List<TeamDefinition> resolvedTeams = teams.isEmpty() && !legacy.teams().isEmpty()
                ? legacy.teams() : teams;
        List<RoleBinding> resolvedBindings = roleBindings.isEmpty() && !legacy.roleBindings().isEmpty()
                ? legacy.roleBindings() : roleBindings;
        List<CategoryDefinition> resolvedCategories = categories.isEmpty() && !legacy.categories().isEmpty()
                ? legacy.categories() : categories;
        List<TagDefinition> resolvedTags = tags.isEmpty() && !legacy.tags().isEmpty()
                ? legacy.tags() : tags;
        List<CollectionDefinition> resolvedCollections = collections.isEmpty() && !legacy.collections().isEmpty()
                ? legacy.collections() : collections;
        PlatformPolicy resolvedPolicy = policy.orElse(legacy.platformPolicy());
        if (resolvedTeams.equals(legacy.teams()) && resolvedBindings.equals(legacy.roleBindings())
                && resolvedCategories.equals(legacy.categories()) && resolvedTags.equals(legacy.tags())
                && resolvedCollections.equals(legacy.collections()) && resolvedPolicy.equals(legacy.platformPolicy())) {
            return legacy;
        }
        return new GovernanceConfiguration(resolvedTeams, resolvedBindings, resolvedCategories, resolvedTags,
                resolvedCollections, resolvedPolicy);
    }

    private void ensureConfigurationRows(GovernanceConfiguration configuration) {
        GovernanceConfiguration safe = configuration == null ? GovernanceConfiguration.empty() : configuration;
        jdbc.update("delete from skill_governance_policy");
        jdbc.update("delete from skill_governance_collection");
        jdbc.update("delete from skill_governance_tag");
        jdbc.update("delete from skill_governance_category");
        jdbc.update("delete from skill_governance_role_binding");
        jdbc.update("delete from skill_governance_team");
        for (TeamDefinition team : safe.teams()) {
            jdbc.update("insert into skill_governance_team (team_id, name, description, owner_user_id, "
                            + "member_user_ids, status, created_at, updated_at) values (?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                    team.teamId(), team.name(), team.description(), team.ownerUserId(), json(team.memberUserIds()),
                    team.status(), timestamp(team.createdAt()), timestamp(team.updatedAt()));
        }
        for (RoleBinding binding : safe.roleBindings()) {
            jdbc.update("insert into skill_governance_role_binding (user_id, role, team_id, status, changed_by, changed_at) "
                            + "values (?, ?, ?, ?, ?, ?)", binding.userId(), binding.role(), binding.teamId(),
                    binding.status(), binding.changedBy(), timestamp(binding.changedAt()));
        }
        for (CategoryDefinition category : safe.categories()) {
            jdbc.update("insert into skill_governance_category (code, display_name, description, sort_order, status, "
                            + "updated_by, updated_at) values (?, ?, ?, ?, ?, ?, ?)", category.code(), category.displayName(),
                    category.description(), category.sortOrder(), category.status(), category.updatedBy(),
                    timestamp(category.updatedAt()));
        }
        for (TagDefinition tag : safe.tags()) {
            jdbc.update("insert into skill_governance_tag (code, display_name, description, sort_order, status, "
                            + "updated_by, updated_at) values (?, ?, ?, ?, ?, ?, ?)", tag.code(), tag.displayName(),
                    tag.description(), tag.sortOrder(), tag.status(), tag.updatedBy(), timestamp(tag.updatedAt()));
        }
        for (CollectionDefinition collection : safe.collections()) {
            jdbc.update("insert into skill_governance_collection (collection_id, name, description, owner_team_id, "
                            + "visibility, skill_ids, sort_order, status, updated_by, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)", collection.collectionId(), collection.name(),
                    collection.description(), collection.ownerTeamId(), collection.visibility(), json(collection.skillIds()),
                    collection.sortOrder(), collection.status(), collection.updatedBy(), timestamp(collection.updatedAt()));
        }
        PlatformPolicy policy = safe.platformPolicy();
        jdbc.update("insert into skill_governance_policy (policy_key, policy_version, page_size_options, max_page_size, "
                        + "minimum_client_version, default_collection_visibility, updated_by, updated_at) "
                        + "values (?, ?, ?::jsonb, ?, ?, ?, ?, ?)", POLICY_KEY, policy.policyVersion(),
                json(policy.pageSizeOptions()), policy.maxPageSize(), policy.minimumClientVersion(),
                policy.defaultCollectionVisibility(), policy.updatedBy(), timestamp(policy.updatedAt()));
    }

    private GovernanceState map(ResultSet resultSet, int ignored) throws SQLException {
        try {
            return new GovernanceState(resultSet.getLong("revision"),
                    objectMapper.readValue(resultSet.getString("state"), GovernanceSnapshot.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("governance state is invalid", exception);
        }
    }

    private SkillVersion mapVersion(ResultSet resultSet, int ignored) throws SQLException {
        try {
            return new SkillVersion(resultSet.getString("package_id"), resultSet.getString("skill_id"),
                    resultSet.getString("version"), resultSet.getString("status"), resultSet.getString("sha256"),
                    resultSet.getLong("size_bytes"), resultSet.getString("artifact_path"),
                    resultSet.getString("uploaded_by"), instant(resultSet, "uploaded_at"),
                    resultSet.getString("published_by"), instant(resultSet, "published_at"),
                    resultSet.getString("review_id"), resultSet.getString("status_reason"),
                    resultSet.getString("replacement_version"), resultSet.getString("status_changed_by"),
                    instant(resultSet, "status_changed_at"), resultSet.getString("risk_level"),
                    objectMapper.readValue(resultSet.getString("security_evidence"), SecurityScanEvidence.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("governance skill version is invalid", exception);
        }
    }

    private ReviewTask mapReview(ResultSet resultSet, int ignored) throws SQLException {
        try {
            return new ReviewTask(resultSet.getString("review_id"), resultSet.getString("package_id"),
                    resultSet.getString("skill_id"), resultSet.getString("version"), resultSet.getString("status"),
                    resultSet.getString("submitted_by"), instant(resultSet, "submitted_at"),
                    resultSet.getString("reviewed_by"), instant(resultSet, "reviewed_at"), resultSet.getString("reason"),
                    resultSet.getString("risk_level"), resultSet.getString("security_reviewed_by"),
                    instant(resultSet, "security_reviewed_at"), resultSet.getString("security_reason"),
                    objectMapper.readValue(resultSet.getString("security_evidence"), SecurityScanEvidence.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("governance review is invalid", exception);
        }
    }

    private TeamDefinition mapTeam(ResultSet resultSet, int ignored) throws SQLException {
        try {
            return new TeamDefinition(resultSet.getString("team_id"), resultSet.getString("name"),
                    resultSet.getString("description"), resultSet.getString("owner_user_id"),
                    stringList(resultSet.getString("member_user_ids")), resultSet.getString("status"),
                    instant(resultSet, "created_at"), instant(resultSet, "updated_at"));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("governance team is invalid", exception);
        }
    }

    private RoleBinding mapRoleBinding(ResultSet resultSet, int ignored) throws SQLException {
        return new RoleBinding(resultSet.getString("user_id"), resultSet.getString("role"),
                resultSet.getString("team_id"), resultSet.getString("status"), resultSet.getString("changed_by"),
                instant(resultSet, "changed_at"));
    }

    private CategoryDefinition mapCategory(ResultSet resultSet, int ignored) throws SQLException {
        return new CategoryDefinition(resultSet.getString("code"), resultSet.getString("display_name"),
                resultSet.getString("description"), resultSet.getInt("sort_order"), resultSet.getString("status"),
                resultSet.getString("updated_by"), instant(resultSet, "updated_at"));
    }

    private TagDefinition mapTag(ResultSet resultSet, int ignored) throws SQLException {
        return new TagDefinition(resultSet.getString("code"), resultSet.getString("display_name"),
                resultSet.getString("description"), resultSet.getInt("sort_order"), resultSet.getString("status"),
                resultSet.getString("updated_by"), instant(resultSet, "updated_at"));
    }

    private CollectionDefinition mapCollection(ResultSet resultSet, int ignored) throws SQLException {
        try {
            return new CollectionDefinition(resultSet.getString("collection_id"), resultSet.getString("name"),
                    resultSet.getString("description"), resultSet.getString("owner_team_id"),
                    resultSet.getString("visibility"), stringList(resultSet.getString("skill_ids")),
                    resultSet.getInt("sort_order"), resultSet.getString("status"), resultSet.getString("updated_by"),
                    instant(resultSet, "updated_at"));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("governance collection is invalid", exception);
        }
    }

    private PlatformPolicy mapPolicy(ResultSet resultSet, int ignored) throws SQLException {
        try {
            return new PlatformPolicy(resultSet.getInt("policy_version"), integerList(resultSet.getString("page_size_options")),
                    resultSet.getInt("max_page_size"), resultSet.getString("minimum_client_version"),
                    resultSet.getString("default_collection_visibility"), resultSet.getString("updated_by"),
                    instant(resultSet, "updated_at"));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("governance policy is invalid", exception);
        }
    }

    private String json(GovernanceSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("governance state cannot be serialized", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("governance configuration cannot be serialized", exception);
        }
    }

    private List<String> stringList(String value) throws JsonProcessingException {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = objectMapper.readValue(value, new TypeReference<List<String>>() { });
        return result == null ? List.of() : result;
    }

    private List<Integer> integerList(String value) throws JsonProcessingException {
        if (value == null || value.isBlank()) return List.of();
        List<Integer> result = objectMapper.readValue(value, new TypeReference<List<Integer>>() { });
        return result == null ? List.of() : result;
    }

    private String securityJson(SecurityScanEvidence evidence) {
        try {
            return objectMapper.writeValueAsString(evidence == null ? SecurityScanEvidence.legacy() : evidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("security evidence cannot be serialized", exception);
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private GovernanceStore.GovernancePersistenceException failure(Throwable cause) {
        if (cause instanceof GovernanceStore.GovernancePersistenceException persistence) return persistence;
        return new GovernanceStore.GovernancePersistenceException(cause);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
