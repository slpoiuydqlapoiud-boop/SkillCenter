package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.quality.OptimizationExperimentAssessment;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QualityRegressionService implements QualityRegressionProbe {
    private static final int MAX_REGRESSIONS = 100;
    private final OptimizationExperimentAssessmentRepository store;
    private final Clock clock;

    @Autowired
    public QualityRegressionService(OptimizationExperimentAssessmentRepository store) {
        this(store, Clock.systemUTC());
    }

    QualityRegressionService(OptimizationExperimentAssessmentRepository store, Clock clock) {
        this.store = store;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public QualityRegressionHealth health() {
        Instant checkedAt = clock.instant();
        try {
            Map<String, OptimizationExperimentAssessment> latestBySkill = new LinkedHashMap<>();
            store.findAll("").stream()
                    .sorted(Comparator.comparing(OptimizationExperimentAssessment::assessedAt).reversed()
                            .thenComparing(OptimizationExperimentAssessment::assessmentId, Comparator.reverseOrder()))
                    .forEach(assessment -> latestBySkill.putIfAbsent(assessment.skillId(), assessment));
            List<OptimizationExperimentAssessment> currentRegressions = latestBySkill.values().stream()
                    .filter(assessment -> OptimizationExperimentAssessment.REGRESSION.equals(assessment.conclusion()))
                    .sorted(Comparator.comparing(OptimizationExperimentAssessment::skillId)
                            .thenComparing(OptimizationExperimentAssessment::candidateVersion)
                            .thenComparing(OptimizationExperimentAssessment::assessmentId))
                    .toList();
            List<QualityRegressionHealth.Regression> regressions = currentRegressions.stream()
                    .limit(MAX_REGRESSIONS)
                    .map(this::regression)
                    .toList();
            if (regressions.isEmpty()) return QualityRegressionHealth.healthy(checkedAt, latestBySkill.size());
            return new QualityRegressionHealth("DEGRADED", "QUALITY_REGRESSIONS_DETECTED", checkedAt,
                    latestBySkill.size(), currentRegressions.size(), regressions);
        } catch (RuntimeException ignored) {
            return QualityRegressionHealth.unavailable(checkedAt);
        }
    }

    private QualityRegressionHealth.Regression regression(OptimizationExperimentAssessment assessment) {
        return new QualityRegressionHealth.Regression(assessment.skillId(), assessment.sourceVersion(),
                assessment.candidateVersion(), assessment.assessmentId(), assessment.conclusion(), assessment.reasonCode());
    }
}
