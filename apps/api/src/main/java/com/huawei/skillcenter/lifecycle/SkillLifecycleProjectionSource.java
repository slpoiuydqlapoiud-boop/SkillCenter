package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.access.SkillScope;
import com.huawei.skillcenter.access.SkillVisibility;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SecurityScanEvidence;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.release.ReleaseRecord;
import com.huawei.skillcenter.release.ReleaseRecordRepository;
import com.huawei.skillcenter.release.ReleaseStatus;
import com.huawei.skillcenter.access.SkillScopeRepository;
import com.huawei.skillcenter.relationship.SkillRelation;
import com.huawei.skillcenter.relationship.SkillRelationStatus;
import com.huawei.skillcenter.relationship.SkillRelationRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SkillLifecycleProjectionSource {
    private static final Comparator<SkillLifecycleVersionRow> LATEST_VERSION_ORDER =
            Comparator.comparing(SkillLifecycleVersionRow::uploadedAt)
                    .thenComparing(SkillLifecycleVersionRow::version)
                    .thenComparing(SkillLifecycleVersionRow::packageId);

    private final GovernanceStore governanceStore;
    private final ReleaseRecordRepository releaseStore;
    private final SkillScopeRepository scopeStore;
    private final SkillRelationRepository relationStore;
    @SuppressWarnings("unused")
    private final Clock clock;

    public SkillLifecycleProjectionSource(GovernanceStore governanceStore,
                                          ReleaseRecordRepository releaseStore,
                                          SkillScopeRepository scopeStore,
                                          SkillRelationRepository relationStore,
                                          Clock clock) {
        this.governanceStore = require(governanceStore, "governanceStore");
        this.releaseStore = require(releaseStore, "releaseStore");
        this.scopeStore = require(scopeStore, "scopeStore");
        this.relationStore = require(relationStore, "relationStore");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public SkillLifecycleProjectionInput read() {
        try {
            List<SkillVersion> sourceVersions = governanceStore.snapshot().versions();
            List<SkillLifecycleVersionRow> versions = normalizeVersions(sourceVersions);
            Map<String, SkillLifecycleVersionRow> versionsByKey = indexVersions(versions);
            validateReplacementVersions(sourceVersions, versionsByKey);
            List<SkillLifecycleReleaseRow> releases = normalizeReleases(releaseStore.findAll(null, null, null, null), versionsByKey);
            List<SkillLifecycleScopeRow> scopes = normalizeScopes(scopeStore.findAll());
            Map<String, SkillLifecycleScopeRow> scopesBySkill = new HashMap<>();
            scopes.forEach(scope -> scopesBySkill.put(scope.skillId(), scope));
            List<SkillLifecycleRelationRow> relations = normalizeRelations(
                    relationStore.findAll(null, null, null, null, null), versionsByKey);
            List<SkillLifecycleSkillRow> skills = normalizeSkills(versions, releases, scopesBySkill);
            return new SkillLifecycleProjectionInput(skills, versions, releases, scopes, relations);
        } catch (SkillLifecycleProjectionSourceInvalidException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private List<SkillLifecycleVersionRow> normalizeVersions(List<SkillVersion> sourceVersions) {
        Map<String, SkillLifecycleVersionRow> versions = new HashMap<>();
        for (SkillVersion source : sourceVersions == null ? List.<SkillVersion>of() : sourceVersions) {
            validateVersionLifecycle(source);
            SkillLifecycleVersionRow row = new SkillLifecycleVersionRow(
                    source.skillId(),
                    source.version(),
                    source.packageId(),
                    normalizeCode(source.status()),
                    source.sha256(),
                    source.sizeBytes(),
                    source.uploadedBy(),
                    source.uploadedAt(),
                    source.publishedAt(),
                    normalizeCode(source.riskLevel()),
                    source.securityEvidence().status(),
                    source.securityEvidence().scannerId(),
                    source.securityEvidence().scannerVersion(),
                    source.securityEvidence().findings().stream()
                            .map(this::normalizeSecurityFinding)
                            .toList());
            if (row.publishedAt() != null && row.publishedAt().isBefore(row.uploadedAt())) {
                throw invalid();
            }
            String key = versionKey(row.skillId(), row.version());
            if (versions.putIfAbsent(key, row) != null) {
                throw invalid();
            }
        }
        return List.copyOf(versions.values());
    }

    private SkillLifecycleSecurityFindingRow normalizeSecurityFinding(SecurityScanEvidence.Finding finding) {
        if (finding == null) {
            throw invalid();
        }
        return new SkillLifecycleSecurityFindingRow(finding.code(), finding.path(), finding.severity());
    }

    private void validateVersionLifecycle(SkillVersion source) {
        String status = normalizeLifecycleStatus(source.status());
        boolean hasPublishedBy = source.publishedBy() != null && !source.publishedBy().isBlank();
        boolean hasPublishedAt = source.publishedAt() != null;
        if (hasPublishedBy != hasPublishedAt) {
            throw invalid();
        }
        if ("published".equals(status) && (!hasPublishedBy || !hasPublishedAt)) {
            throw invalid();
        }
        if (Set.of("deprecated", "withdrawn").contains(status)) {
            if (source.statusReason() == null || source.statusReason().isBlank()) {
                throw invalid();
            }
            if (source.statusChangedBy() == null || source.statusChangedBy().isBlank()) {
                throw invalid();
            }
            if (source.statusChangedAt() == null) {
                throw invalid();
            }
            if (source.statusChangedAt().isBefore(source.uploadedAt())) {
                throw invalid();
            }
        }
    }

    private Map<String, SkillLifecycleVersionRow> indexVersions(List<SkillLifecycleVersionRow> versions) {
        Map<String, SkillLifecycleVersionRow> indexed = new HashMap<>();
        for (SkillLifecycleVersionRow row : versions) {
            indexed.put(versionKey(row.skillId(), row.version()), row);
        }
        return indexed;
    }

    private void validateReplacementVersions(List<SkillVersion> sourceVersions,
                                             Map<String, SkillLifecycleVersionRow> versionsByKey) {
        for (SkillVersion source : sourceVersions == null ? List.<SkillVersion>of() : sourceVersions) {
            String replacementVersion = normalizeOptional(source.replacementVersion());
            if (replacementVersion == null) {
                continue;
            }
            if (replacementVersion.equals(source.version())) {
                throw invalid();
            }
            SkillLifecycleVersionRow target = versionsByKey.get(versionKey(source.skillId(), replacementVersion));
            if (target == null) {
                throw invalid();
            }
            if (!Set.of("PUBLISHED", "DEPRECATED").contains(target.status())) {
                throw invalid();
            }
        }
    }

    private List<SkillLifecycleReleaseRow> normalizeReleases(List<ReleaseRecord> sourceReleases,
                                                             Map<String, SkillLifecycleVersionRow> versionsByKey) {
        List<SkillLifecycleReleaseRow> rows = new ArrayList<>();
        Set<String> releaseIds = new HashSet<>();
        for (ReleaseRecord source : sourceReleases == null ? List.<ReleaseRecord>of() : sourceReleases) {
            if (!versionsByKey.containsKey(versionKey(source.skillId(), source.version()))) {
                throw invalid();
            }
            if (!source.rollbackTargetVersion().isBlank()
                    && !versionsByKey.containsKey(versionKey(source.skillId(), source.rollbackTargetVersion()))) {
                throw invalid();
            }
            SkillLifecycleReleaseRow row = new SkillLifecycleReleaseRow(
                    source.releaseId(),
                    source.skillId(),
                    source.version(),
                    source.targetEnvironment(),
                    source.status(),
                    source.gateSnapshot().outcome(),
                    source.sha256(),
                    source.requestedAt(),
                    source.approvedAt(),
                    source.updatedAt(),
                    source.sourceAssessmentId(),
                    source.rollbackOfReleaseId(),
                    source.rollbackTargetVersion(),
                    source.rollbackTargetReleaseId());
            if (!releaseIds.add(row.releaseId())) {
                throw invalid();
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private List<SkillLifecycleScopeRow> normalizeScopes(List<SkillScope> sourceScopes) {
        List<SkillLifecycleScopeRow> rows = new ArrayList<>();
        Set<String> skillIds = new HashSet<>();
        for (SkillScope source : sourceScopes == null ? List.<SkillScope>of() : sourceScopes) {
            SkillLifecycleScopeRow row = new SkillLifecycleScopeRow(
                    source.skillId(),
                    source.visibility(),
                    source.ownerTeamId(),
                    source.maintainerUserIds().size(),
                    source.revision(),
                    source.declaredAt(),
                    source.updatedAt());
            if (!skillIds.add(row.skillId())) {
                throw invalid();
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private List<SkillLifecycleRelationRow> normalizeRelations(List<SkillRelation> sourceRelations,
                                                               Map<String, SkillLifecycleVersionRow> versionsByKey) {
        List<SkillLifecycleRelationRow> rows = new ArrayList<>();
        Set<String> relationIds = new HashSet<>();
        Set<String> canonicalKeys = new HashSet<>();
        for (SkillRelation source : sourceRelations == null ? List.<SkillRelation>of() : sourceRelations) {
            if (!versionsByKey.containsKey(versionKey(source.sourceSkillId(), source.sourceVersion()))
                    || !versionsByKey.containsKey(versionKey(source.targetSkillId(), source.targetVersion()))) {
                throw invalid();
            }
            SkillLifecycleRelationRow row = new SkillLifecycleRelationRow(
                    source.relationId(),
                    source.sourceSkillId(),
                    source.sourceVersion(),
                    source.targetSkillId(),
                    source.targetVersion(),
                    source.relationType(),
                    source.status(),
                    source.declaredAt(),
                    source.retiredAt());
            if (!relationIds.add(row.relationId())) {
                throw invalid();
            }
            String canonicalKey = String.join("\u0000",
                    row.sourceSkillId(), row.sourceVersion(),
                    row.targetSkillId(), row.targetVersion(),
                    row.relationType().name(), row.status().name());
            if (!canonicalKeys.add(canonicalKey)) {
                throw invalid();
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private List<SkillLifecycleSkillRow> normalizeSkills(List<SkillLifecycleVersionRow> versions,
                                                         List<SkillLifecycleReleaseRow> releases,
                                                         Map<String, SkillLifecycleScopeRow> scopesBySkill) {
        Map<String, List<SkillLifecycleVersionRow>> versionsBySkill = new HashMap<>();
        versions.forEach(version -> versionsBySkill
                .computeIfAbsent(version.skillId(), ignored -> new ArrayList<>())
                .add(version));
        Map<String, Integer> activeReleaseCounts = new HashMap<>();
        releases.stream()
                .filter(release -> !release.status().terminal())
                .forEach(release -> activeReleaseCounts.merge(release.skillId(), 1, Integer::sum));

        List<SkillLifecycleSkillRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<SkillLifecycleVersionRow>> entry : versionsBySkill.entrySet()) {
            List<SkillLifecycleVersionRow> skillVersions = entry.getValue();
            SkillLifecycleVersionRow latest = skillVersions.stream()
                    .max(LATEST_VERSION_ORDER)
                    .orElseThrow(SkillLifecycleProjectionSource::invalid);
            SkillLifecycleScopeRow scope = scopesBySkill.get(entry.getKey());
            SkillVisibility visibility = scope == null ? SkillVisibility.PUBLIC : scope.visibility();
            String ownerTeamId = scope == null ? "" : scope.ownerTeamId();
            int scopeRevision = scope == null ? 0 : scope.scopeRevision();
            int publishedCount = (int) skillVersions.stream()
                    .filter(version -> "PUBLISHED".equals(version.status()))
                    .count();
            rows.add(new SkillLifecycleSkillRow(
                    entry.getKey(),
                    latest.version(),
                    latest.status(),
                    skillVersions.size(),
                    publishedCount,
                    activeReleaseCounts.getOrDefault(entry.getKey(), 0),
                    visibility,
                    ownerTeamId,
                    scopeRevision));
        }
        return List.copyOf(rows);
    }

    private static String normalizeCode(String value) {
        if (value == null) {
            throw invalid();
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeLifecycleStatus(String value) {
        if (value == null) {
            throw invalid();
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String versionKey(String skillId, String version) {
        return skillId + "\u0000" + version;
    }

    private static SkillLifecycleProjectionSourceInvalidException invalid() {
        return new SkillLifecycleProjectionSourceInvalidException();
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
