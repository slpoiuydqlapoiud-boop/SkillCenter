package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.operations.RuntimeOperationsService;
import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptimizationExperimentObservationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T03:00:00Z");
    private final Actor admin = new Actor("admin", "admin");
    private OptimizationExperimentObservationStore observations;
    private OptimizationExperimentStore experiments;
    private RuntimeOperationsService runtimeOperations;
    private GovernanceStore governance;
    private OptimizationExperimentObservationService service;

    @BeforeEach
    void setUp() {
        observations = mock(OptimizationExperimentObservationStore.class);
        experiments = mock(OptimizationExperimentStore.class);
        runtimeOperations = mock(RuntimeOperationsService.class);
        governance = mock(GovernanceStore.class);
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(List.of(new SkillVersion(
                "pkg-1", "skill-a", "1.1.0", "published", "sha", 1, "artifact", "owner", NOW,
                "admin", NOW, "review-1")), List.of(), List.of(), List.of()));
        service = new OptimizationExperimentObservationService(observations, experiments, runtimeOperations,
                governance, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void capturesRedactedRuntimeMetricsOnlyAfterPublishedPromotion() {
        OptimizationExperiment experiment = completedPromotion();
        when(experiments.find("experiment-1")).thenReturn(Optional.of(experiment));
        when(runtimeOperations.snapshot(any())).thenReturn(snapshot());
        when(observations.create(any(OptimizationExperimentObservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OptimizationExperimentObservation result = service.capture("experiment-1",
                new OptimizationExperimentObservationRequest("24h"), admin, "req-observe");

        assertThat(result.candidateVersion()).isEqualTo("1.1.0");
        assertThat(result.totalCalls()).isEqualTo(4);
        assertThat(result.successRate()).isEqualTo(75);
        verify(governance).addAudit(any());
    }

    @Test
    void blocksObservationBeforeCandidateWasPublished() {
        when(experiments.find("experiment-1")).thenReturn(Optional.of(completedPromotion()));
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(List.of(new SkillVersion(
                "pkg-1", "skill-a", "1.1.0", "pending_review", "sha", 1, "artifact", "owner", NOW,
                null, null, "review-1")), List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> service.capture("experiment-1", null, admin, "req-observe"))
                .isInstanceOf(OptimizationExperimentInvalidStateException.class)
                .hasMessageContaining("published");
    }

    private OptimizationExperiment completedPromotion() {
        OptimizationExperimentDecision decision = new OptimizationExperimentDecision(
                "decision-1", "experiment-1", "skill-a", "1.0.0", "1.1.0", "production", "", "", "",
                "smoke", "smoke-v1", "snapshot-1", "benchmark-1", QualityGateStatus.PASSED, "IMPROVED",
                OptimizationExperimentDecision.PROMOTE_CANDIDATE, "BENCHMARK_IMPROVED", "reason", "observe",
                "admin", NOW);
        return new OptimizationExperiment("experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0",
                "production", "", "", "", "smoke", "smoke-v1", OptimizationExperimentStatus.COMPLETED,
                "run-1", "snapshot-1", "benchmark-1", "", "admin", NOW, "admin", NOW, decision);
    }

    private RuntimeOperationsSnapshot snapshot() {
        return new RuntimeOperationsSnapshot("24h", NOW, "production", null,
                new RuntimeOperationsSnapshot.Totals(4, 3, 1, 0, 0, 75),
                new RuntimeOperationsSnapshot.Latency(4, 30, 120, 240), List.of(), List.of(), List.of(), List.of());
    }
}
