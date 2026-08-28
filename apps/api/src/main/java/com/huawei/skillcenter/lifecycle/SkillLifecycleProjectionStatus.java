package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;

import java.util.Set;

public record SkillLifecycleProjectionStatus(
        String backend,
        String state,
        String reasonCode,
        String schemaVersion,
        long revision,
        String sourceSha256,
        int skillCount,
        int versionCount,
        int releaseCount,
        int scopeCount,
        int relationCount) {
    private static final String READY = "READY";
    private static final String EMPTY = "EMPTY";
    private static final String FAIL_CLOSED = "FAIL_CLOSED";
    private static final String NOT_READY = "SKILL_LIFECYCLE_PROJECTION_NOT_READY";
    private static final Set<String> STATES = Set.of(READY, EMPTY, FAIL_CLOSED);

    public SkillLifecycleProjectionStatus {
        backend = normalizeBackend(backend);
        state = STATES.contains(state) ? state : FAIL_CLOSED;
        reasonCode = FAIL_CLOSED.equals(state) ? safeReasonCode(reasonCode) : "";
        schemaVersion = schemaVersion != null && schemaVersion.matches("[0-9]+") ? schemaVersion : null;
        revision = requireNonNegativeLong(revision, "revision");
        sourceSha256 = safeSha256(sourceSha256);
        skillCount = requireNonNegativeInt(skillCount, "skillCount");
        versionCount = requireNonNegativeInt(versionCount, "versionCount");
        releaseCount = requireNonNegativeInt(releaseCount, "releaseCount");
        scopeCount = requireNonNegativeInt(scopeCount, "scopeCount");
        relationCount = requireNonNegativeInt(relationCount, "relationCount");
    }

    public static SkillLifecycleProjectionStatus ready(String backend, String schemaVersion, long revision,
                                                       String sourceSha256, int skillCount, int versionCount,
                                                       int releaseCount, int scopeCount, int relationCount) {
        String state = revision == 0
                && skillCount == 0
                && versionCount == 0
                && releaseCount == 0
                && scopeCount == 0
                && relationCount == 0
                ? EMPTY
                : READY;
        return new SkillLifecycleProjectionStatus(
                backend, state, "", schemaVersion, revision, sourceSha256,
                skillCount, versionCount, releaseCount, scopeCount, relationCount);
    }

    public static SkillLifecycleProjectionStatus failClosed(String backend, String schemaVersion, long revision,
                                                            String sourceSha256, int skillCount, int versionCount,
                                                            int releaseCount, int scopeCount, int relationCount,
                                                            String reasonCode) {
        return new SkillLifecycleProjectionStatus(
                backend, FAIL_CLOSED, reasonCode, schemaVersion, revision, sourceSha256,
                skillCount, versionCount, releaseCount, scopeCount, relationCount);
    }

    @Override
    public String toString() {
        return "SkillLifecycleProjectionStatus[backend=" + backend
                + ", state=" + state
                + ", reasonCode=" + reasonCode
                + ", schemaVersion=" + schemaVersion
                + ", revision=" + revision
                + ", skillCount=" + skillCount
                + ", versionCount=" + versionCount
                + ", releaseCount=" + releaseCount
                + ", scopeCount=" + scopeCount
                + ", relationCount=" + relationCount + "]";
    }

    private static String normalizeBackend(String value) {
        String normalized = PersistenceControlProperties.normalizeBackendValue(value);
        return "postgresql".equals(normalized) || "json".equals(normalized) ? normalized : "unknown";
    }

    private static String safeReasonCode(String value) {
        return NOT_READY.equals(value) ? NOT_READY : NOT_READY;
    }

    private static String safeSha256(String value) {
        if (value != null && value.matches("[a-f0-9]{64}")) {
            return value;
        }
        return "0".repeat(64);
    }

    private static long requireNonNegativeLong(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }

    private static int requireNonNegativeInt(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }
}
