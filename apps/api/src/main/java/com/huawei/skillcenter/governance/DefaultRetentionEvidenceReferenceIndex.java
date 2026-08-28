package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.quality.OptimizationExperiment;
import com.huawei.skillcenter.quality.OptimizationExperimentRepository;
import com.huawei.skillcenter.quality.OptimizationWorkItem;
import com.huawei.skillcenter.quality.OptimizationWorkItemRepository;
import com.huawei.skillcenter.release.ReleaseGateSnapshot;
import com.huawei.skillcenter.release.ReleaseRecord;
import com.huawei.skillcenter.release.ReleaseRecordRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Default index over persisted lifecycle assets that can reference quality evidence. */
@Component
public class DefaultRetentionEvidenceReferenceIndex implements RetentionEvidenceReferenceIndex {
    private final OptimizationWorkItemRepository workItemRepository;
    private final OptimizationExperimentRepository experimentStore;
    private final ReleaseRecordRepository releaseRecordRepository;

    public DefaultRetentionEvidenceReferenceIndex(OptimizationWorkItemRepository workItemRepository,
                                                   OptimizationExperimentRepository experimentStore,
                                                   ReleaseRecordRepository releaseRecordRepository) {
        this.workItemRepository = require(workItemRepository, "workItemRepository");
        this.experimentStore = require(experimentStore, "experimentStore");
        this.releaseRecordRepository = require(releaseRecordRepository, "releaseRecordRepository");
    }

    @Override
    public RetentionProtectionSnapshot snapshot() {
        try {
            List<RetentionEvidenceReference> references = new ArrayList<>();
            for (OptimizationWorkItem workItem : workItemRepository.findAll(null, null, null, null)) {
                addWorkItemReference(references, workItem);
            }
            for (OptimizationExperiment experiment : experimentStore.findAll(null, null, null)) {
                add(references, "EXPERIMENT", experiment.experimentId(), "EVALUATION_RUN",
                        experiment.evaluationRunId());
                add(references, "EXPERIMENT", experiment.experimentId(), "QUALITY_SNAPSHOT",
                        experiment.qualitySnapshotId());
                add(references, "EXPERIMENT", experiment.experimentId(), "BENCHMARK", experiment.benchmarkId());
            }
            for (ReleaseRecord release : releaseRecordRepository.findAll(null, null, null, null)) {
                ReleaseGateSnapshot gate = release.gateSnapshot();
                if (gate == null) continue;
                add(references, "RELEASE", release.releaseId(), "QUALITY_SNAPSHOT", gate.qualitySnapshotId());
                add(references, "RELEASE", release.releaseId(), "COMPATIBILITY_MATRIX",
                        gate.compatibilityMatrixId());
            }
            return RetentionProtectionSnapshot.from(references);
        } catch (RetentionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RetentionException("RETENTION_EVIDENCE_PROTECTION_UNAVAILABLE",
                    "Retention evidence protection is unavailable");
        }
    }

    private static void addWorkItemReference(List<RetentionEvidenceReference> references,
                                             OptimizationWorkItem workItem) {
        if (workItem == null) return;
        add(references, "WORK_ITEM", workItem.workItemId(), workItem.evidenceType(), workItem.evidenceId());
    }

    private static void add(List<RetentionEvidenceReference> references, String sourceType, String sourceId,
                            String evidenceType, String evidenceId) {
        if (sourceId == null || sourceId.isBlank() || evidenceId == null || evidenceId.isBlank()) return;
        if (!List.of("EVALUATION_RUN", "QUALITY_SNAPSHOT", "COMPATIBILITY_MATRIX", "BENCHMARK")
                .contains(evidenceType)) return;
        references.add(new RetentionEvidenceReference(sourceType, sourceId, evidenceType, evidenceId));
    }

    private static <T> T require(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
