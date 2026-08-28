package com.huawei.skillcenter.quality;

import java.util.Set;

/** Immutable IDs that must survive quality-evidence retention cleanup. */
public record QualityEvidenceRetentionProtection(
        Set<String> evaluationRunIds,
        Set<String> qualitySnapshotIds,
        Set<String> compatibilityMatrixIds
) {
    public QualityEvidenceRetentionProtection {
        evaluationRunIds = immutable(evaluationRunIds);
        qualitySnapshotIds = immutable(qualitySnapshotIds);
        compatibilityMatrixIds = immutable(compatibilityMatrixIds);
    }

    public static QualityEvidenceRetentionProtection empty() {
        return new QualityEvidenceRetentionProtection(Set.of(), Set.of(), Set.of());
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
