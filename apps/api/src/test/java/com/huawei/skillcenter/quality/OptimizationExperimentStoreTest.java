package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationExperimentStoreTest {
    @Test
    void persistsAndRestoresExperimentsAndPreventsTwoActiveExperimentsForOneWorkItem() throws Exception {
        Path statePath = Files.createTempDirectory("optimization-experiments").resolve("state.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OptimizationExperimentStore first = new OptimizationExperimentStore(statePath, mapper);
        OptimizationExperiment value = new OptimizationExperiment("experiment-1", "work-1", "skill-a",
                "1.0.0", "1.1.0", "mock", "", "", "", "smoke", "smoke-v1",
                OptimizationExperimentStatus.QUEUED, "", "", "", "", "admin",
                Instant.parse("2026-08-24T00:00:00Z"), "admin", Instant.parse("2026-08-24T00:00:00Z"));

        first.create(value);

        assertThat(new OptimizationExperimentStore(statePath, mapper).find("experiment-1"))
                .contains(value);
        assertThatThrownBy(() -> first.create(new OptimizationExperiment("experiment-2", "work-1", "skill-a",
                "1.0.0", "1.1.0", "mock", "", "", "", "smoke", "smoke-v1",
                OptimizationExperimentStatus.QUEUED, "", "", "", "", "admin",
                Instant.parse("2026-08-24T00:00:00Z"), "admin", Instant.parse("2026-08-24T00:00:00Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active experiment");
    }

    @Test
    void persistsAndRestoresDecisionSnapshotInsideExperimentRecord() throws Exception {
        Path statePath = Files.createTempDirectory("optimization-experiment-decision").resolve("state.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OptimizationExperimentStore first = new OptimizationExperimentStore(statePath, mapper);
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        OptimizationExperimentDecision decision = new OptimizationExperimentDecision(
                "decision-experiment-1", "experiment-1", "skill-a", "1.0.0", "1.1.0", "mock", "", "", "",
                "smoke", "smoke-v1", "snapshot-1", "benchmark-1", QualityGateStatus.PASSED, "IMPROVED",
                OptimizationExperimentDecision.PROMOTE_CANDIDATE, "BENCHMARK_IMPROVED", "safe reason",
                "提交人工发布审核", "admin", now);
        OptimizationExperiment value = new OptimizationExperiment("experiment-1", "work-1", "skill-a",
                "1.0.0", "1.1.0", "mock", "", "", "", "smoke", "smoke-v1",
                OptimizationExperimentStatus.COMPLETED, "run-1", "snapshot-1", "benchmark-1", "", "admin",
                now, "admin", now, decision);

        first.create(value);

        assertThat(new OptimizationExperimentStore(statePath, mapper).find("experiment-1"))
                .contains(value);

        OptimizationExperimentDecision changedDecision = new OptimizationExperimentDecision(
                "decision-experiment-1", "experiment-1", "skill-a", "1.0.0", "1.1.0", "mock", "", "", "",
                "smoke", "smoke-v1", "snapshot-1", "benchmark-1", QualityGateStatus.PASSED, "IMPROVED",
                OptimizationExperimentDecision.ITERATE, "BENCHMARK_NEEDS_ITERATION", "changed", "继续优化", "admin", now);
        OptimizationExperiment replacement = new OptimizationExperiment("experiment-1", "work-1", "skill-a",
                "1.0.0", "1.1.0", "mock", "", "", "", "smoke", "smoke-v1",
                OptimizationExperimentStatus.COMPLETED, "run-1", "snapshot-1", "benchmark-1", "", "admin",
                now, "admin", now, changedDecision);
        assertThatThrownBy(() -> first.replace(replacement))
                .isInstanceOf(OptimizationExperimentConflictException.class)
                .hasMessageContaining("decision");
    }
}
