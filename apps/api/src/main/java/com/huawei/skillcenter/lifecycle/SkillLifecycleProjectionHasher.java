package com.huawei.skillcenter.lifecycle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;

public final class SkillLifecycleProjectionHasher {
    private static final Comparator<SkillLifecycleSkillRow> SKILL_ORDER =
            Comparator.comparing(SkillLifecycleSkillRow::skillId);
    private static final Comparator<SkillLifecycleVersionRow> VERSION_ORDER =
            Comparator.comparing(SkillLifecycleVersionRow::skillId)
                    .thenComparing(SkillLifecycleVersionRow::version)
                    .thenComparing(SkillLifecycleVersionRow::packageId);
    private static final Comparator<SkillLifecycleReleaseRow> RELEASE_ORDER =
            Comparator.comparing(SkillLifecycleReleaseRow::releaseId)
                    .thenComparing(SkillLifecycleReleaseRow::skillId)
                    .thenComparing(SkillLifecycleReleaseRow::version);
    private static final Comparator<SkillLifecycleScopeRow> SCOPE_ORDER =
            Comparator.comparing(SkillLifecycleScopeRow::skillId);
    private static final Comparator<SkillLifecycleRelationRow> RELATION_ORDER =
            Comparator.comparing(SkillLifecycleRelationRow::relationId)
                    .thenComparing(SkillLifecycleRelationRow::sourceSkillId)
                    .thenComparing(SkillLifecycleRelationRow::sourceVersion)
                    .thenComparing(SkillLifecycleRelationRow::targetSkillId)
                    .thenComparing(SkillLifecycleRelationRow::targetVersion);
    private static final Comparator<SkillLifecycleSecurityFindingRow> SECURITY_FINDING_ORDER =
            Comparator.comparing(SkillLifecycleSecurityFindingRow::code)
                    .thenComparing(SkillLifecycleSecurityFindingRow::path)
                    .thenComparing(SkillLifecycleSecurityFindingRow::severity);

    private SkillLifecycleProjectionHasher() {
    }

    public static String hash(SkillLifecycleProjectionInput input) {
        if (input == null) throw new IllegalArgumentException("input is required");
        StringBuilder canonical = new StringBuilder(1024);
        appendSection(canonical, "skills");
        input.skills().stream().sorted(SKILL_ORDER).forEach(row -> appendRow(canonical,
                row.skillId(), row.latestVersion(), row.latestStatus(), Integer.toString(row.versionCount()),
                Integer.toString(row.publishedVersionCount()), Integer.toString(row.activeReleaseCount()),
                row.visibility().name(), row.ownerTeamId(), Integer.toString(row.scopeRevision())));
        appendSection(canonical, "versions");
        input.versions().stream().sorted(VERSION_ORDER).forEach(row -> appendRow(canonical,
                row.skillId(), row.version(), row.packageId(), row.status(), row.sha256(),
                Long.toString(row.sizeBytes()), row.uploadedBy(), instant(row.uploadedAt()),
                instant(row.publishedAt()), row.riskLevel(), row.securityStatus(), row.securityScannerId(),
                row.securityScannerVersion(), Integer.toString(row.securityFindings().size())));
        input.versions().stream().sorted(VERSION_ORDER).forEach(row ->
                row.securityFindings().stream().sorted(SECURITY_FINDING_ORDER).forEach(finding -> appendRow(canonical,
                        row.skillId(), row.version(), "finding", finding.code(), finding.path(), finding.severity())));
        appendSection(canonical, "releases");
        input.releases().stream().sorted(RELEASE_ORDER).forEach(row -> appendRow(canonical,
                row.releaseId(), row.skillId(), row.version(), row.targetEnvironment().name(), row.status().name(),
                row.gateOutcome(), row.sha256(), instant(row.requestedAt()), instant(row.approvedAt()),
                instant(row.updatedAt()), row.sourceAssessmentId(), row.rollbackOfReleaseId(),
                row.rollbackTargetVersion(), row.rollbackTargetReleaseId()));
        appendSection(canonical, "scopes");
        input.scopes().stream().sorted(SCOPE_ORDER).forEach(row -> appendRow(canonical,
                row.skillId(), row.visibility().name(), row.ownerTeamId(), Integer.toString(row.maintainerCount()),
                Integer.toString(row.scopeRevision()), instant(row.declaredAt()), instant(row.updatedAt())));
        appendSection(canonical, "relations");
        input.relations().stream().sorted(RELATION_ORDER).forEach(row -> appendRow(canonical,
                row.relationId(), row.sourceSkillId(), row.sourceVersion(), row.targetSkillId(), row.targetVersion(),
                row.relationType().name(), row.status().name(), instant(row.declaredAt()), instant(row.retiredAt())));
        return sha256(canonical.toString());
    }

    private static void appendSection(StringBuilder canonical, String section) {
        canonical.append(section).append('\n');
    }

    private static void appendRow(StringBuilder canonical, String... fields) {
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) canonical.append('|');
            canonical.append(escape(fields[index]));
        }
        canonical.append('\n');
    }

    private static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String escape(String value) {
        String normalized = value == null ? "" : value;
        return normalized.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
