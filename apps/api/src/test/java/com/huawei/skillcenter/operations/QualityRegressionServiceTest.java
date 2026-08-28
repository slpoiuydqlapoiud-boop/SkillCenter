package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessment;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessmentStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityRegressionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void selectsLatestAssessmentPerSkillAndCountsOnlyCurrentRegressions() throws Exception {
        OptimizationExperimentAssessmentStore store = store(
                assessment("old-regression", "skill-a", "1.0.0", "1.1.0", "REGRESSION", NOW.minusSeconds(300)),
                assessment("new-healthy", "skill-a", "1.1.0", "1.2.0", "HEALTHY", NOW.minusSeconds(200)),
                assessment("skill-b-regression", "skill-b", "2.0.0", "2.1.0", "REGRESSION", NOW.minusSeconds(100)));

        QualityRegressionHealth health = new QualityRegressionService(store, Clock.fixed(NOW, ZoneOffset.UTC)).health();

        assertThat(health.status()).isEqualTo("DEGRADED");
        assertThat(health.reasonCode()).isEqualTo("QUALITY_REGRESSIONS_DETECTED");
        assertThat(health.assessedSkillCount()).isEqualTo(2);
        assertThat(health.regressionCount()).isEqualTo(1);
        assertThat(health.regressions()).extracting(QualityRegressionHealth.Regression::skillId)
                .containsExactly("skill-b");
        assertThat(health.regressions()).extracting(QualityRegressionHealth.Regression::assessmentId)
                .containsExactly("skill-b-regression");
    }

    @Test
    void returnsStableOrderingAndHealthySignalWhenNoCurrentRegressionExists() throws Exception {
        OptimizationExperimentAssessmentStore store = store(
                assessment("skill-b-healthy", "skill-b", "2.0.0", "2.1.0", "HEALTHY", NOW.minusSeconds(100)),
                assessment("skill-a-inconclusive", "skill-a", "1.0.0", "1.1.0", "INCONCLUSIVE", NOW.minusSeconds(200)));

        QualityRegressionHealth health = new QualityRegressionService(store, Clock.fixed(NOW, ZoneOffset.UTC)).health();

        assertThat(health.status()).isEqualTo("HEALTHY");
        assertThat(health.reasonCode()).isEqualTo("QUALITY_REGRESSIONS_HEALTHY");
        assertThat(health.regressionCount()).isZero();
        assertThat(health.regressions()).isEmpty();
    }

    @Test
    void convertsAssessmentStoreFailureToNotReadyWithoutLeakingCause() {
        OptimizationExperimentAssessmentStore store = mock(OptimizationExperimentAssessmentStore.class);
        when(store.findAll("")).thenThrow(new IllegalStateException("database password=secret"));

        QualityRegressionHealth health = new QualityRegressionService(store, Clock.fixed(NOW, ZoneOffset.UTC)).health();

        assertThat(health.status()).isEqualTo("NOT_READY");
        assertThat(health.reasonCode()).isEqualTo("QUALITY_REGRESSION_SIGNAL_UNAVAILABLE");
        assertThat(health.regressionCount()).isZero();
        assertThat(health.toString()).doesNotContain("secret");
    }

    private static OptimizationExperimentAssessmentStore store(OptimizationExperimentAssessment... values)
            throws Exception {
        var path = Files.createTempDirectory("quality-regression").resolve("assessments.json");
        OptimizationExperimentAssessmentStore store = new OptimizationExperimentAssessmentStore(
                new ObjectMapper().findAndRegisterModules(), path.toString());
        for (OptimizationExperimentAssessment value : values) store.create(value);
        return store;
    }

    private static OptimizationExperimentAssessment assessment(String id, String skillId, String sourceVersion,
                                                               String candidateVersion, String conclusion,
                                                               Instant assessedAt) {
        OptimizationExperimentAssessment.Metrics candidate = new OptimizationExperimentAssessment.Metrics(
                10, "HEALTHY".equals(conclusion) ? 10 : 7, 0, 0, 0,
                "HEALTHY".equals(conclusion) ? 100 : 70, "HEALTHY".equals(conclusion) ? 80 : 120, assessedAt);
        OptimizationExperimentAssessment.Metrics baseline = new OptimizationExperimentAssessment.Metrics(
                10, 10, 0, 0, 0, 100, 80, assessedAt.minusSeconds(60));
        String recommended = "REGRESSION".equals(conclusion) ? "CREATE_FOLLOW_UP" : "KEEP";
        return new OptimizationExperimentAssessment(id, "experiment-" + id, "work-" + id, skillId,
                sourceVersion, candidateVersion, "production", "runtime-a", "mcp-a", "llm-a", "24h", "observation-" + id,
                candidate, baseline, 95, 1000, 5, conclusion,
                "REGRESSION".equals(conclusion) ? "CANDIDATE_REGRESSION" : "POST_RELEASE_HEALTHY",
                recommended, "REGRESSION".equals(conclusion) ? "CREATE_FOLLOW_UP" : "KEEP", "", "admin", assessedAt);
    }
}
