package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationExperimentObservationStoreTest {
    @Test
    void observationsSurviveStoreRestartAndAreNewestFirst() throws Exception {
        var directory = Files.createTempDirectory("optimization-observations");
        var path = directory.resolve("state.json");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new OptimizationExperimentObservationStore(path, mapper);
        store.create(observation("observation-1", Instant.parse("2026-08-24T01:00:00Z")));
        store.create(observation("observation-2", Instant.parse("2026-08-24T02:00:00Z")));

        var recovered = new OptimizationExperimentObservationStore(path, mapper);

        assertThat(recovered.findAll("experiment-1")).extracting(OptimizationExperimentObservation::observationId)
                .containsExactly("observation-2", "observation-1");
    }

    @Test
    void countsAndDeletesExpiredObservations() throws Exception {
        var directory = Files.createTempDirectory("optimization-observation-retention");
        var path = directory.resolve("state.json");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new OptimizationExperimentObservationStore(path, mapper);
        store.create(observation("observation-old", Instant.parse("2026-01-01T00:00:00Z")));
        store.create(observation("observation-new", Instant.parse("2026-08-24T02:00:00Z")));

        assertThat(store.countBefore(Instant.parse("2026-08-01T00:00:00Z"))).isEqualTo(1);
        assertThat(store.deleteBefore(Instant.parse("2026-08-01T00:00:00Z"))).isEqualTo(1);
        assertThat(store.findAll("")).extracting(OptimizationExperimentObservation::observationId)
                .containsExactly("observation-new");
    }

    private OptimizationExperimentObservation observation(String id, Instant capturedAt) {
        return new OptimizationExperimentObservation(id, "experiment-1", "skill-a", "1.1.0", "production",
                "", "", "", "24h", capturedAt, "admin", 4, 3, 1, 0, 0, 75, 120, "CAPTURED");
    }
}
