package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationExperimentAssessmentStoreTest {
    @Test
    void assessmentsSurviveRestartAndReturnNewestFirst() throws Exception {
        var directory = Files.createTempDirectory("optimization-assessments");
        var path = directory.resolve("state.json");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new OptimizationExperimentAssessmentStore(path, mapper);
        store.create(assessment("assessment-1", Instant.parse("2026-08-24T04:00:00Z")));
        store.create(assessment("assessment-2", Instant.parse("2026-08-24T05:00:00Z")));

        var recovered = new OptimizationExperimentAssessmentStore(path, mapper);

        assertThat(recovered.findAll("experiment-1")).extracting(OptimizationExperimentAssessment::assessmentId)
                .containsExactly("assessment-2", "assessment-1");
        assertThat(recovered.find("assessment-1")).isPresent();
    }

    @Test
    void countsAndDeletesExpiredAssessments() throws Exception {
        var directory = Files.createTempDirectory("optimization-assessment-retention");
        var path = directory.resolve("state.json");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new OptimizationExperimentAssessmentStore(path, mapper);
        store.create(assessment("assessment-old", Instant.parse("2026-01-01T00:00:00Z")));
        store.create(assessment("assessment-new", Instant.parse("2026-08-24T05:00:00Z")));

        assertThat(store.countBefore(Instant.parse("2026-08-01T00:00:00Z"))).isEqualTo(1);
        assertThat(store.deleteBefore(Instant.parse("2026-08-01T00:00:00Z"))).isEqualTo(1);
        assertThat(store.findAll("")).extracting(OptimizationExperimentAssessment::assessmentId)
                .containsExactly("assessment-new");
    }

    private OptimizationExperimentAssessment assessment(String id, Instant time) {
        var metrics = new OptimizationExperimentAssessment.Metrics(5, 5, 0, 0, 0, 100, 80, time);
        return new OptimizationExperimentAssessment(id, "experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0",
                "production", "", "", "", "24h", "observation-1", metrics, metrics, 95, 1_000, 5,
                OptimizationExperimentAssessment.HEALTHY, "POST_RELEASE_HEALTHY",
                OptimizationExperimentAssessment.KEEP, OptimizationExperimentAssessment.KEEP, "", "admin", time);
    }
}
