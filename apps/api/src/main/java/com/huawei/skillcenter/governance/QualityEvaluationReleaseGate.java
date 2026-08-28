package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.quality.QualityEvaluationService;
import com.huawei.skillcenter.quality.QualityGateStatus;
import com.huawei.skillcenter.quality.QualitySnapshot;
import com.huawei.skillcenter.quality.QualityEvidenceRepository;
import com.huawei.skillcenter.quality.CompatibilityMatrixRun;
import com.huawei.skillcenter.quality.CompatibilityMatrixStatus;
import com.huawei.skillcenter.quality.OptimizationExperiment;
import com.huawei.skillcenter.quality.OptimizationExperimentDecision;
import com.huawei.skillcenter.quality.OptimizationExperimentStatus;
import com.huawei.skillcenter.quality.OptimizationExperimentRepository;
import com.huawei.skillcenter.release.ReleaseGateSnapshot;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.time.Instant;
import java.util.List;

/** Uses the latest completed quality snapshot as the publish decision evidence. */
@Component
public class QualityEvaluationReleaseGate implements QualityReleaseGate {
    private final QualityEvaluationService qualityEvaluationService;
    private final QualityEvidenceRepository qualityEvidenceRepository;
    private final OptimizationExperimentRepository optimizationExperimentStore;

    public QualityEvaluationReleaseGate(QualityEvaluationService qualityEvaluationService) {
        this(qualityEvaluationService, null, null);
    }

    public QualityEvaluationReleaseGate(QualityEvaluationService qualityEvaluationService,
                                        QualityEvidenceRepository qualityEvidenceRepository) {
        this(qualityEvaluationService, qualityEvidenceRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public QualityEvaluationReleaseGate(QualityEvaluationService qualityEvaluationService,
                                        QualityEvidenceRepository qualityEvidenceRepository,
                                        OptimizationExperimentRepository optimizationExperimentStore) {
        this.qualityEvaluationService = qualityEvaluationService;
        this.qualityEvidenceRepository = qualityEvidenceRepository;
        this.optimizationExperimentStore = optimizationExperimentStore;
    }

    @Override
    public void ensurePublishable(String skillId, String version) {
        evaluate(skillId, version);
    }

    @Override
    public ReleaseGateSnapshot evaluate(String skillId, String version) {
        OptimizationExperiment latestExperiment = ensureOptimizationExperimentPublishable(skillId, version);
        CompatibilityMatrixRun matrix = null;
        if (qualityEvidenceRepository != null) {
            matrix = qualityEvidenceRepository.load().matrixRuns().stream()
                    .filter(candidate -> candidate.releaseGateRequired()
                            && skillId.equals(candidate.skillId()) && version.equals(candidate.skillVersion()))
                    .max(Comparator.comparing(CompatibilityMatrixRun::createdAt))
                    .orElse(null);
            if (matrix != null) {
                if (matrix.status() != CompatibilityMatrixStatus.COMPLETED) {
                    throw new QualityGateBlockedException(skillId, version,
                            java.util.List.of("COMPATIBILITY_MATRIX_INCOMPLETE"));
                }
                if (matrix.gateStatus() == QualityGateStatus.BLOCKED) {
                    throw new QualityGateBlockedException(skillId, version,
                            matrix.gateReasons().isEmpty()
                                    ? java.util.List.of("COMPATIBILITY_MATRIX_BLOCKED") : matrix.gateReasons());
                }
            }
        }
        QualitySnapshot latest = qualityEvaluationService.snapshots(skillId).stream()
                .filter(snapshot -> version.equals(snapshot.skillVersion()))
                .max(Comparator.comparing(QualitySnapshot::measuredAt))
                .orElse(null);
        // Preserve legacy uploads that have no quality evidence yet; once evidence exists, enforce it.
        if (latest != null && latest.gateStatus() == QualityGateStatus.BLOCKED) {
            throw new QualityGateBlockedException(skillId, version, latest.gateReasons());
        }
        boolean hasEvidence = latest != null || matrix != null || latestExperiment != null;
        return new ReleaseGateSnapshot(Instant.now(), hasEvidence ? "PASSED" : "NO_EVIDENCE", List.of(),
                latest == null ? "" : latest.snapshotId(),
                latestExperiment == null ? "" : latestExperiment.experimentId(),
                latestExperiment == null || latestExperiment.decision() == null ? ""
                        : latestExperiment.decision().decision(),
                matrix == null ? "" : matrix.matrixRunId(),
                latest != null ? latest.dataSource() : matrix != null ? matrix.dataSource() : "all",
                latest != null ? latest.suiteId() : matrix != null ? matrix.suiteId() : "",
                latest != null ? latest.suiteVersion() : matrix != null ? matrix.suiteVersion() : "",
                latest == null ? "" : latest.runtimeId(), latest == null ? "" : latest.mcpServerId(),
                latest == null ? "" : latest.llmProviderId());
    }

    private OptimizationExperiment ensureOptimizationExperimentPublishable(String skillId, String version) {
        if (optimizationExperimentStore == null) return null;
        var matching = optimizationExperimentStore.findAll(skillId, null, null).stream()
                .filter(experiment -> version.equals(experiment.candidateVersion()))
                .toList();
        if (matching.isEmpty()) return null;
        if (matching.stream().anyMatch(experiment -> OptimizationExperimentStatus.QUEUED.equals(experiment.status())
                || OptimizationExperimentStatus.RUNNING.equals(experiment.status()))) {
            throw new QualityGateBlockedException(skillId, version,
                    java.util.List.of("OPTIMIZATION_EXPERIMENT_INCOMPLETE"));
        }
        OptimizationExperiment latest = matching.stream()
                .filter(experiment -> OptimizationExperimentStatus.isTerminal(experiment.status()))
                .max(Comparator.comparing(OptimizationExperiment::updatedAt)
                        .thenComparing(OptimizationExperiment::experimentId))
                .orElse(null);
        if (latest == null) return null;
        if (OptimizationExperimentStatus.FAILED.equals(latest.status())) {
            throw new QualityGateBlockedException(skillId, version,
                    java.util.List.of("OPTIMIZATION_EXPERIMENT_FAILED"));
        }
        if (OptimizationExperimentStatus.CANCELLED.equals(latest.status())) {
            throw new QualityGateBlockedException(skillId, version,
                    java.util.List.of("OPTIMIZATION_EXPERIMENT_CANCELLED"));
        }
        if (latest.decision() == null) {
            throw new QualityGateBlockedException(skillId, version,
                    java.util.List.of("OPTIMIZATION_DECISION_REQUIRED"));
        }
        if (!OptimizationExperimentDecision.PROMOTE_CANDIDATE.equals(latest.decision().decision())) {
            throw new QualityGateBlockedException(skillId, version,
                    java.util.List.of("OPTIMIZATION_DECISION_BLOCKED"));
        }
        return latest;
    }
}
