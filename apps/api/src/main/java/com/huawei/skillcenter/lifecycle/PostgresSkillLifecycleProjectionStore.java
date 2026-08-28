package com.huawei.skillcenter.lifecycle;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class PostgresSkillLifecycleProjectionStore implements SkillLifecycleProjectionRepository {
    private static final String BACKEND = "postgresql";
    private static final String PROJECTION_KEY = "skill-lifecycle";
    private static final String SCHEMA_VERSION = "3";
    private static final String NOT_READY = "SKILL_LIFECYCLE_PROJECTION_NOT_READY";
    private static final String IMPORT_FAILED = "SKILL_LIFECYCLE_PROJECTION_IMPORT_FAILED";
    private static final int MAX_IMPACT_DEPTH = 8;
    private static final int MAX_IMPACT_NODES = 100;
    private static final String SELECT_META = """
            select schema_version, revision, source_sha256, source_generated_at, imported_at,
                   skill_count, version_count, release_count, scope_count, relation_count
              from skill_lifecycle_projection_meta
             where projection_key = ?
            """;
    private static final String SELECT_META_FOR_UPDATE = SELECT_META + " for update";
    private static final String COUNT_SKILLS = "select count(*) from skill_lifecycle_skill_projection";
    private static final String COUNT_VERSIONS = "select count(*) from skill_lifecycle_version_projection";
    private static final String COUNT_RELEASES = "select count(*) from skill_lifecycle_release_projection";
    private static final String COUNT_SCOPES = "select count(*) from skill_lifecycle_scope_projection";
    private static final String COUNT_RELATIONS = "select count(*) from skill_lifecycle_relation_projection";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresSkillLifecycleProjectionStore(JdbcTemplate jdbcTemplate,
                                                 DataSourceTransactionManager transactionManager) {
        this.jdbc = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public SkillLifecycleProjectionStatus status() {
        try {
            MetaRow row = readMeta(SELECT_META);
            if (row == null) {
                return SkillLifecycleProjectionStatus.failClosed(
                        BACKEND, SCHEMA_VERSION, 0L, "0".repeat(64), 0, 0, 0, 0, 0, NOT_READY);
            }
            return SkillLifecycleProjectionStatus.ready(
                    BACKEND,
                    Integer.toString(row.schemaVersion()),
                    row.revision(),
                    row.sourceSha256(),
                    row.skillCount(),
                    row.versionCount(),
                    row.releaseCount(),
                    row.scopeCount(),
                    row.relationCount());
        } catch (RuntimeException exception) {
            return SkillLifecycleProjectionStatus.failClosed(
                    BACKEND, SCHEMA_VERSION, safeRevision(), safeSourceSha256(), 0, 0, 0, 0, 0, NOT_READY);
        }
    }

    @Override
    public java.util.Optional<Instant> importedAt() {
        try {
            MetaRow row = readMeta(SELECT_META);
            return row == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(row.importedAt());
        } catch (RuntimeException exception) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public SkillLifecycleProjectionImportResult replace(SkillLifecycleProjectionSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        try {
            return transactions.execute(status -> replaceInTransaction(snapshot));
        } catch (RuntimeException exception) {
            return new SkillLifecycleProjectionImportResult(
                    false,
                    false,
                    safeRevision(),
                    snapshot.sourceSha256(),
                    snapshot.skills().size(),
                    snapshot.versions().size(),
                    snapshot.releases().size(),
                    snapshot.scopes().size(),
                    snapshot.relations().size(),
                    IMPORT_FAILED);
        }
    }

    @Override
    public List<SkillLifecycleProjectionView> findSkills(SkillLifecycleProjectionQuery query) {
        SkillLifecycleProjectionQuery resolved = query == null ? SkillLifecycleProjectionQuery.defaults() : query;
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select v.skill_id,
                       v.version,
                       v.package_id,
                       v.status,
                       s.latest_version,
                       s.latest_status,
                       s.version_count,
                       s.published_version_count,
                       s.active_release_count,
                       s.visibility,
                       s.owner_team_id
                  from skill_lifecycle_version_projection v
                  join skill_lifecycle_skill_projection s on s.skill_id = v.skill_id
                 where 1 = 1
                """);
        appendEquals(sql, parameters, "v.skill_id", resolved.skillId());
        appendEquals(sql, parameters, "v.version", resolved.version());
        appendEquals(sql, parameters, "v.status", resolved.status());
        if (resolved.environment() != null) {
            sql.append("""
                     and exists (
                            select 1
                              from skill_lifecycle_release_projection r
                             where r.skill_id = v.skill_id
                               and r.version = v.version
                               and r.target_environment = ?
                        )
                    """);
            parameters.add(resolved.environment().name());
        }
        sql.append(" order by v.skill_id, v.version");
        List<SkillViewRow> rows = jdbc.query(sql.toString(), this::mapSkillView, parameters.toArray());
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, List<SkillLifecycleProjectionView.ReleaseView>> releasesByVersion = findReleaseViews(
                resolved.skillId(),
                resolved.version(),
                resolved.environment());
        return rows.stream()
                .map(row -> new SkillLifecycleProjectionView(
                        row.skillId(),
                        row.version(),
                        row.packageId(),
                        row.status(),
                        row.latestVersion(),
                        row.latestStatus(),
                        row.versionCount(),
                        row.publishedVersionCount(),
                        row.activeReleaseCount(),
                        row.visibility(),
                        row.ownerTeamId(),
                        releasesByVersion.getOrDefault(versionKey(row.skillId(), row.version()), List.of())))
                .toList();
    }

    @Override
    public SkillLifecycleImpactView findImpact(String skillId, String version) {
        String normalizedSkillId = skillId == null ? "" : skillId.trim();
        String normalizedVersion = version == null ? "" : version.trim();
        Map<String, SkillLifecycleVersionRow> versionsByKey = findVersionsByKey();
        String rootKey = versionKey(normalizedSkillId, normalizedVersion);
        SkillLifecycleVersionRow root = versionsByKey.get(rootKey);
        if (root == null) {
            throw new IllegalArgumentException("skill version is required");
        }

        Map<String, List<SkillLifecycleRelationRow>> reverse = findActiveReverseRelations();
        Queue<Visit> queue = new ArrayDeque<>();
        queue.add(new Visit(rootKey, 0));
        Set<String> seen = new HashSet<>();
        seen.add(rootKey);
        List<NodeRef> discovered = new ArrayList<>();
        Set<String> impactedKeys = new HashSet<>();
        boolean truncated = false;
        boolean stopTraversal = false;
        while (!queue.isEmpty() && !stopTraversal) {
            Visit current = queue.remove();
            for (SkillLifecycleRelationRow relation : reverse.getOrDefault(current.key(), List.of())) {
                int depth = current.depth() + 1;
                String nextKey = versionKey(relation.sourceSkillId(), relation.sourceVersion());
                if (!seen.add(nextKey)) {
                    continue;
                }
                if (depth > MAX_IMPACT_DEPTH) {
                    truncated = true;
                    continue;
                }
                if (discovered.size() >= MAX_IMPACT_NODES) {
                    truncated = true;
                    stopTraversal = true;
                    break;
                }
                if (!versionsByKey.containsKey(nextKey)) {
                    throw new SkillLifecycleProjectionSourceInvalidException();
                }
                discovered.add(new NodeRef(
                        relation.relationId(),
                        nextKey,
                        relation.relationType().name(),
                        relation.status().name(),
                        depth));
                impactedKeys.add(nextKey);
                queue.add(new Visit(nextKey, depth));
            }
        }
        Map<String, List<SkillLifecycleReleaseRow>> releasesByVersion = findReleasesByVersionKeys(impactedKeys);
        List<SkillLifecycleImpactView.Node> nodes = discovered.stream()
                .map(ref -> {
                    SkillLifecycleVersionRow affected = versionsByKey.get(ref.versionKey());
                    if (affected == null) {
                        throw new SkillLifecycleProjectionSourceInvalidException();
                    }
                    List<SkillLifecycleReleaseRow> versionReleases = releasesByVersion.getOrDefault(ref.versionKey(), List.of());
                    boolean productionPromoted = versionReleases.stream()
                            .anyMatch(release -> release.targetEnvironment() == com.huawei.skillcenter.release.ReleaseEnvironment.PRODUCTION
                                    && release.status() == com.huawei.skillcenter.release.ReleaseStatus.PROMOTED
                                    && release.sha256().equals(affected.sha256()));
                    return new SkillLifecycleImpactView.Node(
                            ref.relationId(),
                            affected.skillId(),
                            affected.version(),
                            ref.relationType(),
                            ref.relationStatus(),
                            ref.depth(),
                            productionPromoted,
                            toReleaseViews(versionReleases, null));
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        nodes.sort(Comparator.comparingInt(SkillLifecycleImpactView.Node::depth)
                .thenComparing(SkillLifecycleImpactView.Node::skillId)
                .thenComparing(SkillLifecycleImpactView.Node::version)
                .thenComparing(SkillLifecycleImpactView.Node::relationId));
        return new SkillLifecycleImpactView(root.skillId(), root.version(), truncated, nodes);
    }

    private SkillLifecycleProjectionImportResult replaceInTransaction(SkillLifecycleProjectionSnapshot snapshot) {
        MetaRow current = readMeta(SELECT_META_FOR_UPDATE);
        if (current == null) {
            throw new IllegalStateException("projection meta row is missing");
        }
        if (current.schemaVersion() < Integer.parseInt(SCHEMA_VERSION)) {
            throw new IllegalStateException("projection schema version is not ready");
        }
        if (current.sourceSha256().equals(snapshot.sourceSha256())) {
            return new SkillLifecycleProjectionImportResult(
                    false,
                    true,
                    current.revision(),
                    snapshot.sourceSha256(),
                    current.skillCount(),
                    current.versionCount(),
                    current.releaseCount(),
                    current.scopeCount(),
                    current.relationCount(),
                    "");
        }
        long nextRevision = Math.addExact(current.revision(), 1L);
        deleteRows();
        insertSkills(snapshot);
        insertVersions(snapshot);
        insertScopes(snapshot);
        insertReleases(snapshot);
        insertRelations(snapshot);
        verifyCounts(snapshot);
        int updated = jdbc.update("""
                update skill_lifecycle_projection_meta
                   set schema_version = ?,
                       revision = ?,
                       source_sha256 = ?,
                       source_generated_at = ?,
                       imported_at = current_timestamp,
                       skill_count = ?,
                       version_count = ?,
                       release_count = ?,
                       scope_count = ?,
                       relation_count = ?
                 where projection_key = ?
                   and revision = ?
                """,
                Integer.parseInt(SCHEMA_VERSION),
                nextRevision,
                snapshot.sourceSha256(),
                Timestamp.from(snapshot.sourceGeneratedAt()),
                snapshot.skills().size(),
                snapshot.versions().size(),
                snapshot.releases().size(),
                snapshot.scopes().size(),
                snapshot.relations().size(),
                PROJECTION_KEY,
                current.revision());
        if (updated != 1) {
            throw new IllegalStateException("projection meta update conflict");
        }
        return new SkillLifecycleProjectionImportResult(
                true,
                false,
                nextRevision,
                snapshot.sourceSha256(),
                snapshot.skills().size(),
                snapshot.versions().size(),
                snapshot.releases().size(),
                snapshot.scopes().size(),
                snapshot.relations().size(),
                "");
    }

    private MetaRow readMeta(String sql) {
        List<MetaRow> rows = jdbc.query(sql, this::mapMeta, PROJECTION_KEY);
        if (rows.size() != 1) {
            return null;
        }
        return rows.getFirst();
    }

    private MetaRow mapMeta(ResultSet resultSet, int rowNum) throws SQLException {
        return new MetaRow(
                resultSet.getInt("schema_version"),
                resultSet.getLong("revision"),
                resultSet.getString("source_sha256"),
                resultSet.getTimestamp("source_generated_at").toInstant(),
                resultSet.getTimestamp("imported_at").toInstant(),
                resultSet.getInt("skill_count"),
                resultSet.getInt("version_count"),
                resultSet.getInt("release_count"),
                resultSet.getInt("scope_count"),
                resultSet.getInt("relation_count"));
    }

    private SkillViewRow mapSkillView(ResultSet resultSet, int rowNum) throws SQLException {
        return new SkillViewRow(
                resultSet.getString("skill_id"),
                resultSet.getString("version"),
                resultSet.getString("package_id"),
                resultSet.getString("status"),
                resultSet.getString("latest_version"),
                resultSet.getString("latest_status"),
                resultSet.getInt("version_count"),
                resultSet.getInt("published_version_count"),
                resultSet.getInt("active_release_count"),
                resultSet.getString("visibility"),
                resultSet.getString("owner_team_id"));
    }

    private Map<String, SkillLifecycleVersionRow> findVersionsByKey() {
        List<SkillLifecycleVersionRow> rows = jdbc.query("""
                select skill_id,
                       version,
                       package_id,
                       status,
                       sha256,
                       size_bytes,
                       uploaded_by,
                       uploaded_at,
                       published_at,
                       risk_level,
                       security_status,
                       security_scanner_id,
                       security_scanner_version
                  from skill_lifecycle_version_projection
                 order by skill_id, version
                """, this::mapVersionRow);
        Map<String, List<SkillLifecycleSecurityFindingRow>> findingsByKey = new HashMap<>();
        jdbc.query("""
                select skill_id, version, finding_code, finding_path, finding_severity
                  from skill_lifecycle_version_security_finding_projection
                 order by skill_id, version, finding_index
                """, resultSet -> {
            String key = versionKey(resultSet.getString("skill_id"), resultSet.getString("version"));
            findingsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(
                    new SkillLifecycleSecurityFindingRow(
                            resultSet.getString("finding_code"),
                            resultSet.getString("finding_path"),
                            resultSet.getString("finding_severity")));
        });
        Map<String, SkillLifecycleVersionRow> versionsByKey = new HashMap<>();
        rows.forEach(row -> versionsByKey.put(versionKey(row.skillId(), row.version()),
                row.withSecurityFindings(findingsByKey.getOrDefault(versionKey(row.skillId(), row.version()), List.of()))));
        return versionsByKey;
    }

    private SkillLifecycleVersionRow mapVersionRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp publishedAt = resultSet.getTimestamp("published_at");
        return new SkillLifecycleVersionRow(
                resultSet.getString("skill_id"),
                resultSet.getString("version"),
                resultSet.getString("package_id"),
                resultSet.getString("status"),
                resultSet.getString("sha256"),
                resultSet.getLong("size_bytes"),
                resultSet.getString("uploaded_by"),
                resultSet.getTimestamp("uploaded_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant(),
                resultSet.getString("risk_level"),
                resultSet.getString("security_status"),
                resultSet.getString("security_scanner_id"),
                resultSet.getString("security_scanner_version"),
                List.of());
    }

    private Map<String, List<SkillLifecycleRelationRow>> findActiveReverseRelations() {
        List<SkillLifecycleRelationRow> rows = jdbc.query("""
                select relation_id,
                       source_skill_id,
                       source_version,
                       target_skill_id,
                       target_version,
                       relation_type,
                       status,
                       declared_at,
                       retired_at
                  from skill_lifecycle_relation_projection
                 where status = ?
                 order by source_skill_id, source_version, relation_id
                """, this::mapRelationRow, com.huawei.skillcenter.relationship.SkillRelationStatus.ACTIVE.name());
        Map<String, List<SkillLifecycleRelationRow>> reverse = new HashMap<>();
        for (SkillLifecycleRelationRow row : rows) {
            reverse.computeIfAbsent(versionKey(row.targetSkillId(), row.targetVersion()), ignored -> new ArrayList<>())
                    .add(row);
        }
        reverse.values().forEach(values -> values.sort(Comparator.comparing(SkillLifecycleRelationRow::sourceSkillId)
                .thenComparing(SkillLifecycleRelationRow::sourceVersion)
                .thenComparing(SkillLifecycleRelationRow::relationId)));
        return reverse;
    }

    private SkillLifecycleRelationRow mapRelationRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp retiredAt = resultSet.getTimestamp("retired_at");
        return new SkillLifecycleRelationRow(
                resultSet.getString("relation_id"),
                resultSet.getString("source_skill_id"),
                resultSet.getString("source_version"),
                resultSet.getString("target_skill_id"),
                resultSet.getString("target_version"),
                com.huawei.skillcenter.relationship.SkillRelationType.valueOf(resultSet.getString("relation_type")),
                com.huawei.skillcenter.relationship.SkillRelationStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("declared_at").toInstant(),
                retiredAt == null ? null : retiredAt.toInstant());
    }

    private Map<String, List<SkillLifecycleReleaseRow>> findReleasesByVersion(String skillId,
                                                                              String version,
                                                                              com.huawei.skillcenter.release.ReleaseEnvironment environment) {
        String normalizedSkillId = normalize(skillId);
        String normalizedVersion = normalize(version);
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select release_id,
                       skill_id,
                       version,
                       target_environment,
                       status,
                       gate_outcome,
                       sha256,
                       requested_at,
                       approved_at,
                       updated_at,
                       source_assessment_id,
                       rollback_of_release_id,
                       rollback_target_version,
                       rollback_target_release_id
                  from skill_lifecycle_release_projection
                 where 1 = 1
                """);
        appendEquals(sql, parameters, "skill_id", normalizedSkillId);
        appendEquals(sql, parameters, "version", normalizedVersion);
        if (environment != null) {
            sql.append(" and target_environment = ?");
            parameters.add(environment.name());
        }
        sql.append(" order by requested_at desc, release_id");
        List<SkillLifecycleReleaseRow> rows = jdbc.query(sql.toString(), this::mapReleaseRow, parameters.toArray());
        Map<String, List<SkillLifecycleReleaseRow>> releasesByVersion = new HashMap<>();
        for (SkillLifecycleReleaseRow row : rows) {
            releasesByVersion.computeIfAbsent(versionKey(row.skillId(), row.version()), ignored -> new ArrayList<>())
                    .add(row);
        }
        return releasesByVersion;
    }

    private Map<String, List<SkillLifecycleProjectionView.ReleaseView>> findReleaseViews(String skillId,
                                                                                         String version,
                                                                                         com.huawei.skillcenter.release.ReleaseEnvironment environment) {
        Map<String, List<SkillLifecycleProjectionView.ReleaseView>> releasesByVersion = new HashMap<>();
        findReleasesByVersion(skillId, version, environment).forEach((key, rows) ->
                releasesByVersion.put(key, toReleaseViews(rows, environment)));
        return releasesByVersion;
    }

    private Map<String, List<SkillLifecycleReleaseRow>> findReleasesByVersionKeys(Set<String> versionKeys) {
        if (versionKeys == null || versionKeys.isEmpty()) {
            return Map.of();
        }
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select release_id,
                       skill_id,
                       version,
                       target_environment,
                       status,
                       gate_outcome,
                       sha256,
                       requested_at,
                       approved_at,
                       updated_at,
                       source_assessment_id,
                       rollback_of_release_id,
                       rollback_target_version,
                       rollback_target_release_id
                  from skill_lifecycle_release_projection
                 where (skill_id, version) in (
                """);
        int index = 0;
        for (String versionKey : versionKeys) {
            KeyParts parts = parseVersionKey(versionKey);
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("(?, ?)");
            parameters.add(parts.skillId());
            parameters.add(parts.version());
            index++;
        }
        sql.append(") order by requested_at desc, release_id");
        List<SkillLifecycleReleaseRow> rows = jdbc.query(sql.toString(), this::mapReleaseRow, parameters.toArray());
        Map<String, List<SkillLifecycleReleaseRow>> releasesByVersion = new HashMap<>();
        for (SkillLifecycleReleaseRow row : rows) {
            releasesByVersion.computeIfAbsent(versionKey(row.skillId(), row.version()), ignored -> new ArrayList<>())
                    .add(row);
        }
        return releasesByVersion;
    }

    private SkillLifecycleReleaseRow mapReleaseRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp approvedAt = resultSet.getTimestamp("approved_at");
        return new SkillLifecycleReleaseRow(
                resultSet.getString("release_id"),
                resultSet.getString("skill_id"),
                resultSet.getString("version"),
                com.huawei.skillcenter.release.ReleaseEnvironment.valueOf(resultSet.getString("target_environment")),
                com.huawei.skillcenter.release.ReleaseStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("gate_outcome"),
                resultSet.getString("sha256"),
                resultSet.getTimestamp("requested_at").toInstant(),
                approvedAt == null ? null : approvedAt.toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                resultSet.getString("source_assessment_id"),
                resultSet.getString("rollback_of_release_id"),
                resultSet.getString("rollback_target_version"),
                resultSet.getString("rollback_target_release_id"));
    }

    private List<SkillLifecycleProjectionView.ReleaseView> toReleaseViews(List<SkillLifecycleReleaseRow> releases,
                                                                          com.huawei.skillcenter.release.ReleaseEnvironment environmentFilter) {
        return releases.stream()
                .filter(release -> environmentFilter == null || release.targetEnvironment() == environmentFilter)
                .sorted(Comparator.comparing(SkillLifecycleReleaseRow::requestedAt).reversed()
                        .thenComparing(SkillLifecycleReleaseRow::releaseId))
                .map(release -> new SkillLifecycleProjectionView.ReleaseView(
                        release.releaseId(),
                        release.targetEnvironment().name(),
                        release.status().name(),
                        release.gateOutcome()))
                .toList();
    }

    private void appendEquals(StringBuilder sql, List<Object> parameters, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" and ").append(column).append(" = ?");
            parameters.add(value);
        }
    }

    private void deleteRows() {
        jdbc.update("delete from skill_lifecycle_relation_projection");
        jdbc.update("delete from skill_lifecycle_release_projection");
        jdbc.update("delete from skill_lifecycle_version_security_finding_projection");
        jdbc.update("delete from skill_lifecycle_scope_projection");
        jdbc.update("delete from skill_lifecycle_version_projection");
        jdbc.update("delete from skill_lifecycle_skill_projection");
    }

    private void insertSkills(SkillLifecycleProjectionSnapshot snapshot) {
        snapshot.skills().stream()
                .sorted(Comparator.comparing(SkillLifecycleSkillRow::skillId))
                .forEach(row -> jdbc.update("""
                                insert into skill_lifecycle_skill_projection (
                                    skill_id, latest_version, latest_status, version_count,
                                    published_version_count, active_release_count, visibility,
                                    owner_team_id, scope_revision, source_sha256, updated_at
                                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        row.skillId(),
                        row.latestVersion(),
                        row.latestStatus(),
                        row.versionCount(),
                        row.publishedVersionCount(),
                        row.activeReleaseCount(),
                        row.visibility().name(),
                        row.ownerTeamId(),
                        row.scopeRevision(),
                        snapshot.sourceSha256(),
                        Timestamp.from(snapshot.sourceGeneratedAt())));
    }

    private void insertVersions(SkillLifecycleProjectionSnapshot snapshot) {
        snapshot.versions().stream()
                .sorted(Comparator.comparing(SkillLifecycleVersionRow::skillId)
                        .thenComparing(SkillLifecycleVersionRow::version))
                .forEach(row -> jdbc.update("""
                                insert into skill_lifecycle_version_projection (
                                    skill_id, version, package_id, status, sha256, size_bytes,
                       uploaded_by, uploaded_at, published_at, risk_level,
                                    security_status, security_scanner_id, security_scanner_version,
                                    source_sha256, updated_at
                                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        row.skillId(),
                        row.version(),
                        row.packageId(),
                        row.status(),
                        row.sha256(),
                        row.sizeBytes(),
                        row.uploadedBy(),
                        Timestamp.from(row.uploadedAt()),
                        row.publishedAt() == null ? null : Timestamp.from(row.publishedAt()),
                        row.riskLevel(),
                        row.securityStatus(),
                        row.securityScannerId(),
                        row.securityScannerVersion(),
                        snapshot.sourceSha256(),
                        Timestamp.from(snapshot.sourceGeneratedAt())));
        snapshot.versions().stream()
                .sorted(Comparator.comparing(SkillLifecycleVersionRow::skillId)
                        .thenComparing(SkillLifecycleVersionRow::version))
                .forEach(row -> {
                    List<SkillLifecycleSecurityFindingRow> findings = row.securityFindings().stream()
                            .sorted(Comparator.comparing(SkillLifecycleSecurityFindingRow::code)
                                    .thenComparing(SkillLifecycleSecurityFindingRow::path)
                                    .thenComparing(SkillLifecycleSecurityFindingRow::severity))
                            .toList();
                    for (int index = 0; index < findings.size(); index++) {
                        SkillLifecycleSecurityFindingRow finding = findings.get(index);
                        jdbc.update("""
                                insert into skill_lifecycle_version_security_finding_projection (
                                    skill_id, version, finding_index, finding_code, finding_path, finding_severity
                                ) values (?, ?, ?, ?, ?, ?)
                                """,
                                row.skillId(), row.version(), index, finding.code(), finding.path(), finding.severity());
                    }
                });
    }

    private void insertScopes(SkillLifecycleProjectionSnapshot snapshot) {
        snapshot.scopes().stream()
                .sorted(Comparator.comparing(SkillLifecycleScopeRow::skillId))
                .forEach(row -> jdbc.update("""
                                insert into skill_lifecycle_scope_projection (
                                    skill_id, visibility, owner_team_id, maintainer_count,
                                    scope_revision, declared_at, updated_at
                                ) values (?, ?, ?, ?, ?, ?, ?)
                                """,
                        row.skillId(),
                        row.visibility().name(),
                        row.ownerTeamId(),
                        row.maintainerCount(),
                        row.scopeRevision(),
                        Timestamp.from(row.declaredAt()),
                        Timestamp.from(row.updatedAt())));
    }

    private void insertReleases(SkillLifecycleProjectionSnapshot snapshot) {
        snapshot.releases().stream()
                .sorted(Comparator.comparing(SkillLifecycleReleaseRow::releaseId))
                .forEach(row -> jdbc.update("""
                                insert into skill_lifecycle_release_projection (
                                    release_id, skill_id, version, target_environment, status,
                                    gate_outcome, sha256, requested_at, approved_at, updated_at,
                                    source_assessment_id, rollback_of_release_id,
                                    rollback_target_version, rollback_target_release_id
                                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        row.releaseId(),
                        row.skillId(),
                        row.version(),
                        row.targetEnvironment().name(),
                        row.status().name(),
                        row.gateOutcome(),
                        row.sha256(),
                        Timestamp.from(row.requestedAt()),
                        row.approvedAt() == null ? null : Timestamp.from(row.approvedAt()),
                        Timestamp.from(row.updatedAt()),
                        row.sourceAssessmentId(),
                        row.rollbackOfReleaseId(),
                        row.rollbackTargetVersion(),
                        row.rollbackTargetReleaseId()));
    }

    private void insertRelations(SkillLifecycleProjectionSnapshot snapshot) {
        snapshot.relations().stream()
                .sorted(Comparator.comparing(SkillLifecycleRelationRow::relationId))
                .forEach(row -> jdbc.update("""
                                insert into skill_lifecycle_relation_projection (
                                    relation_id, source_skill_id, source_version, target_skill_id,
                                    target_version, relation_type, status, declared_at, retired_at
                                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        row.relationId(),
                        row.sourceSkillId(),
                        row.sourceVersion(),
                        row.targetSkillId(),
                        row.targetVersion(),
                        row.relationType().name(),
                        row.status().name(),
                        Timestamp.from(row.declaredAt()),
                        row.retiredAt() == null ? null : Timestamp.from(row.retiredAt())));
    }

    private void verifyCounts(SkillLifecycleProjectionSnapshot snapshot) {
        assertExpectedCount(COUNT_SKILLS, snapshot.skills().size());
        assertExpectedCount(COUNT_VERSIONS, snapshot.versions().size());
        assertExpectedCount(COUNT_RELEASES, snapshot.releases().size());
        assertExpectedCount(COUNT_SCOPES, snapshot.scopes().size());
        assertExpectedCount(COUNT_RELATIONS, snapshot.relations().size());
    }

    private void assertExpectedCount(String sql, int expected) {
        Integer actual = jdbc.queryForObject(sql, Integer.class);
        if (actual == null || actual != expected) {
            throw new IllegalStateException("projection row count mismatch");
        }
    }

    private long safeRevision() {
        try {
            MetaRow row = readMeta(SELECT_META);
            return row == null ? 0L : row.revision();
        } catch (DataAccessException exception) {
            return 0L;
        }
    }

    private String safeSourceSha256() {
        try {
            MetaRow row = readMeta(SELECT_META);
            return row == null ? "0".repeat(64) : row.sourceSha256();
        } catch (DataAccessException exception) {
            return "0".repeat(64);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String versionKey(String skillId, String version) {
        return skillId + "\u0000" + version;
    }

    private static KeyParts parseVersionKey(String key) {
        int separator = key.indexOf('\u0000');
        if (separator < 0) {
            throw new IllegalArgumentException("invalid version key");
        }
        return new KeyParts(key.substring(0, separator), key.substring(separator + 1));
    }

    private record MetaRow(
            int schemaVersion,
            long revision,
            String sourceSha256,
            Instant sourceGeneratedAt,
            Instant importedAt,
            int skillCount,
            int versionCount,
            int releaseCount,
            int scopeCount,
            int relationCount) {
    }

    private record SkillViewRow(
            String skillId,
            String version,
            String packageId,
            String status,
            String latestVersion,
            String latestStatus,
            int versionCount,
            int publishedVersionCount,
            int activeReleaseCount,
            String visibility,
            String ownerTeamId) {
    }

    private record NodeRef(
            String relationId,
            String versionKey,
            String relationType,
            String relationStatus,
            int depth) {
    }

    private record KeyParts(String skillId, String version) {
    }

    private record Visit(String key, int depth) {
    }
}
