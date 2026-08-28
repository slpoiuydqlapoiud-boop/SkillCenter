package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.quality.QualityEvidenceRetentionProtection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Immutable retention protection state frozen for one preview. */
public record RetentionProtectionSnapshot(
        Set<RetentionEvidenceReference> references,
        Set<String> evaluationRunIds,
        Set<String> qualitySnapshotIds,
        Set<String> compatibilityMatrixIds,
        Set<String> benchmarkIds,
        int referenceCount,
        String fingerprint
) {
    private static final Set<String> SUPPORTED_EVIDENCE_TYPES = Set.of(
            "EVALUATION_RUN", "QUALITY_SNAPSHOT", "COMPATIBILITY_MATRIX", "BENCHMARK");

    public RetentionProtectionSnapshot {
        references = immutableReferences(references);
        evaluationRunIds = immutable(evaluationRunIds);
        qualitySnapshotIds = immutable(qualitySnapshotIds);
        compatibilityMatrixIds = immutable(compatibilityMatrixIds);
        benchmarkIds = immutable(benchmarkIds);
        if (referenceCount < 0 || referenceCount != references.size()) {
            throw new IllegalArgumentException("referenceCount must match references");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint is required");
        }
    }

    public static RetentionProtectionSnapshot from(Collection<RetentionEvidenceReference> values) {
        TreeSet<RetentionEvidenceReference> normalized = new TreeSet<>();
        if (values != null) {
            for (RetentionEvidenceReference reference : values) {
                if (reference == null) throw new IllegalArgumentException("reference must not be null");
                if (!SUPPORTED_EVIDENCE_TYPES.contains(reference.evidenceType())) {
                    throw new IllegalArgumentException("unsupported evidenceType: " + reference.evidenceType());
                }
                normalized.add(reference);
            }
        }
        Set<String> runIds = idsFor(normalized, "EVALUATION_RUN");
        Set<String> snapshotIds = idsFor(normalized, "QUALITY_SNAPSHOT");
        Set<String> matrixIds = idsFor(normalized, "COMPATIBILITY_MATRIX");
        Set<String> benchmarkIds = idsFor(normalized, "BENCHMARK");
        String fingerprint = fingerprint(normalized);
        return new RetentionProtectionSnapshot(normalized, runIds, snapshotIds, matrixIds,
                benchmarkIds, normalized.size(), fingerprint);
    }

    public static RetentionProtectionSnapshot empty() {
        return from(List.of());
    }

    public QualityEvidenceRetentionProtection qualityEvidenceProtection() {
        return new QualityEvidenceRetentionProtection(evaluationRunIds, qualitySnapshotIds,
                compatibilityMatrixIds);
    }

    private static Set<String> idsFor(Collection<RetentionEvidenceReference> references, String type) {
        Set<String> ids = new TreeSet<>();
        references.stream().filter(reference -> type.equals(reference.evidenceType()))
                .map(RetentionEvidenceReference::evidenceId).forEach(ids::add);
        return Set.copyOf(ids);
    }

    private static Set<RetentionEvidenceReference> immutableReferences(
            Set<RetentionEvidenceReference> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static String fingerprint(Collection<RetentionEvidenceReference> references) {
        String canonical = references.stream().sorted().map(RetentionEvidenceReference::canonical)
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
