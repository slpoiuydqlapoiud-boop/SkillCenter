package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Persistence port for quality runs, immutable snapshots, suites and active rules. */
public interface QualityEvidenceRepository {
    @FunctionalInterface
    interface StateUpdate {
        QualityEvidenceState apply(QualityEvidenceState state);
    }

    QualityEvidenceState load();

    void save(QualityEvidenceState state);

    QualityEvidenceState update(StateUpdate update);

    void clear();

    default long countBefore(Instant cutoff) {
        return countBefore(cutoff, QualityEvidenceRetentionProtection.empty());
    }

    default long countBefore(Instant cutoff, QualityEvidenceRetentionProtection protection) {
        requireCutoff(cutoff);
        QualityEvidenceState state = load();
        QualityEvidenceState retained = retainedState(state, cutoff, protection);
        return removedCount(state, retained);
    }

    default long countCompatibilityMatricesBefore(Instant cutoff) {
        return countCompatibilityMatricesBefore(cutoff, QualityEvidenceRetentionProtection.empty());
    }

    default long countCompatibilityMatricesBefore(Instant cutoff,
                                                  QualityEvidenceRetentionProtection protection) {
        requireCutoff(cutoff);
        Set<String> protectedMatrixIds = protection == null ? Set.of() : protection.compatibilityMatrixIds();
        return load().matrixRuns().stream().filter(matrix -> {
            Instant timestamp = matrix.completedAt() == null ? matrix.createdAt() : matrix.completedAt();
            return timestamp != null && timestamp.isBefore(cutoff)
                    && !protectedMatrixIds.contains(matrix.matrixRunId());
        }).count();
    }

    default int deleteBefore(Instant cutoff) {
        return deleteBefore(cutoff, QualityEvidenceRetentionProtection.empty());
    }

    default int deleteBefore(Instant cutoff, QualityEvidenceRetentionProtection protection) {
        requireCutoff(cutoff);
        QualityEvidenceState state = load();
        QualityEvidenceState retained = retainedState(state, cutoff, protection);
        int removed = Math.toIntExact(removedCount(state, retained));
        if (removed > 0) save(retained);
        return removed;
    }

    private static QualityEvidenceState retainedState(QualityEvidenceState state, Instant cutoff,
                                                      QualityEvidenceRetentionProtection protection) {
        QualityEvidenceRetentionProtection safeProtection = protection == null
                ? QualityEvidenceRetentionProtection.empty() : protection;
        Set<String> protectedRunIds = new HashSet<>(safeProtection.evaluationRunIds());
        // The current validator requires a quality snapshot ID to equal its backing run ID.
        protectedRunIds.addAll(safeProtection.qualitySnapshotIds());
        Set<String> protectedMatrixIds = new HashSet<>(safeProtection.compatibilityMatrixIds());
        state.matrixCases().stream()
                .filter(matrixCase -> protectedMatrixIds.contains(matrixCase.matrixRunId()))
                .map(CompatibilityMatrixCase::evaluationRunId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(protectedRunIds::add);

        Set<String> expiredRunIds = state.runs().stream().filter(run -> {
            Instant timestamp = run.completedAt() == null ? run.createdAt() : run.completedAt();
            return timestamp != null && timestamp.isBefore(cutoff) && !protectedRunIds.contains(run.id());
        }).map(EvaluationRun::id).collect(java.util.stream.Collectors.toSet());
        var runs = state.runs().stream().filter(run -> !expiredRunIds.contains(run.id())).toList();
        var snapshots = state.snapshots().stream()
                .filter(snapshot -> !expiredRunIds.contains(snapshot.snapshotId())
                        && (!isBefore(snapshot.measuredAt(), cutoff)
                        || safeProtection.qualitySnapshotIds().contains(snapshot.snapshotId())))
                .toList();
        var caseResults = state.caseResults().stream()
                .filter(result -> !expiredRunIds.contains(result.runId())
                        && (!isBefore(result.evaluatedAt(), cutoff)
                        || protectedRunIds.contains(result.runId())))
                .toList();

        Set<String> expiredMatrixIds = state.matrixRuns().stream().filter(matrix -> {
            Instant timestamp = matrix.completedAt() == null ? matrix.createdAt() : matrix.completedAt();
            return timestamp != null && timestamp.isBefore(cutoff)
                    && !protectedMatrixIds.contains(matrix.matrixRunId());
        }).map(CompatibilityMatrixRun::matrixRunId).collect(java.util.stream.Collectors.toSet());
        var matrixRuns = state.matrixRuns().stream()
                .filter(matrix -> !expiredMatrixIds.contains(matrix.matrixRunId())).toList();
        var matrixCases = state.matrixCases().stream()
                .filter(matrixCase -> !expiredMatrixIds.contains(matrixCase.matrixRunId())).toList();
        return new QualityEvidenceState(state.suites(), state.rules(), runs, snapshots, caseResults,
                matrixRuns, matrixCases);
    }

    private static long removedCount(QualityEvidenceState state, QualityEvidenceState retained) {
        return state.runs().size() - retained.runs().size()
                + state.snapshots().size() - retained.snapshots().size()
                + state.caseResults().size() - retained.caseResults().size()
                + state.matrixRuns().size() - retained.matrixRuns().size()
                + state.matrixCases().size() - retained.matrixCases().size();
    }

    private static boolean isBefore(Instant value, Instant cutoff) {
        return value != null && value.isBefore(cutoff);
    }

    private static void requireCutoff(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
    }
}
