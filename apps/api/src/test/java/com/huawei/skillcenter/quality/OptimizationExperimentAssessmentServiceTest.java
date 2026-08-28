package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.operations.RuntimeOperationsQuery;
import com.huawei.skillcenter.operations.RuntimeOperationsService;
import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

class OptimizationExperimentAssessmentServiceTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-24T04:00:00Z");
    private final Actor admin = new Actor("admin", "admin");
    private OptimizationExperimentAssessmentStore assessments;
    private OptimizationExperimentStore experiments;
    private OptimizationExperimentObservationStore observations;
    private RuntimeOperationsService runtimeOperations;
    private OptimizationSuggestionThresholdsStore thresholds;
    private GovernanceStore governance;
    private OptimizationWorkItemService workItems;
    private OptimizationExperimentAssessmentService service;

    @BeforeEach
    void setUp() {
        assessments = mock(OptimizationExperimentAssessmentStore.class);
        experiments = mock(OptimizationExperimentStore.class);
        observations = mock(OptimizationExperimentObservationStore.class);
        runtimeOperations = mock(RuntimeOperationsService.class);
        thresholds = mock(OptimizationSuggestionThresholdsStore.class);
        governance = mock(GovernanceStore.class);
        workItems = mock(OptimizationWorkItemService.class);
        when(thresholds.get()).thenReturn(new OptimizationSuggestionThresholds(95, 1_000, 5));
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(List.of(
                new SkillVersion("pkg-1", "skill-a", "1.1.0", "published", "sha", 1, "artifact", "owner",
                        OBSERVED_AT, "admin", OBSERVED_AT, "review-1")), List.of(), List.of(), List.of()));
        service = new OptimizationExperimentAssessmentService(assessments, experiments, observations,
                runtimeOperations, thresholds, governance, workItems, Clock.fixed(OBSERVED_AT, ZoneOffset.UTC));
    }

    @Test
    void comparesBaselineAtCandidateObservationEndTimeAndAuditsConclusion() {
        when(experiments.find("experiment-1")).thenReturn(Optional.of(experiment()));
        when(observations.find("observation-1")).thenReturn(Optional.of(candidateObservation()));
        when(runtimeOperations.snapshot(any())).thenReturn(baselineSnapshot());
        when(assessments.create(any(OptimizationExperimentAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OptimizationExperimentAssessment result = service.assess("experiment-1",
                new OptimizationExperimentAssessmentRequest("observation-1", "KEEP", "保留当前版本"), admin, "req-assess");

        assertThat(result.conclusion()).isEqualTo(OptimizationExperimentAssessment.HEALTHY);
        assertThat(result.observationId()).isEqualTo("observation-1");
        assertThat(result.baseline().successRate()).isEqualTo(95);
        ArgumentCaptor<RuntimeOperationsQuery> query = ArgumentCaptor.forClass(RuntimeOperationsQuery.class);
        verify(runtimeOperations).snapshot(query.capture());
        assertThat(query.getValue().version()).isEqualTo("1.0.0");
        assertThat(query.getValue().now()).isEqualTo(OBSERVED_AT);
        verify(governance).addAudit(any());
    }

    @Test
    void explicitFollowUpActionCreatesAndAuditsLinkedOptimizationWorkItem() {
        when(experiments.find("experiment-1")).thenReturn(Optional.of(experiment()));
        when(observations.find("observation-1")).thenReturn(Optional.of(candidateObservation()));
        when(runtimeOperations.snapshot(any())).thenReturn(baselineSnapshot());
        when(assessments.create(any(OptimizationExperimentAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        OptimizationWorkItem followUp = mock(OptimizationWorkItem.class);
        when(followUp.workItemId()).thenReturn("work-follow-up-assessment");
        when(workItems.createPostReleaseFollowUp(any(OptimizationExperimentAssessment.class), any(Actor.class),
                any(String.class))).thenReturn(followUp);

        OptimizationExperimentAssessment result = service.assess("experiment-1",
                new OptimizationExperimentAssessmentRequest("observation-1", "CREATE_FOLLOW_UP", ""),
                admin, "req-follow-up");

        assertThat(result.action()).isEqualTo(OptimizationExperimentAssessment.CREATE_FOLLOW_UP);
        verify(workItems).createPostReleaseFollowUp(result, admin, "req-follow-up");
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(governance).addAudit(audit.capture());
        assertThat(audit.getValue().metadata()).containsEntry(
                "followUpWorkItemId", "work-follow-up-assessment");
    }

    @Test
    void rejectsObservationFromAnotherExperimentContext() {
        when(experiments.find("experiment-1")).thenReturn(Optional.of(experiment()));
        when(observations.find("observation-1")).thenReturn(Optional.of(new OptimizationExperimentObservation(
                "observation-1", "other-experiment", "skill-a", "1.1.0", "production", "", "", "", "24h",
                OBSERVED_AT, "admin", 5, 5, 0, 0, 0, 100, 80, "CAPTURED")));

        assertThatThrownBy(() -> service.assess("experiment-1",
                new OptimizationExperimentAssessmentRequest("observation-1", "KEEP", ""), admin, "req-assess"))
                .isInstanceOf(OptimizationExperimentInvalidStateException.class)
                .hasMessageContaining("context");
    }

    @Test
    void rejectsAssessmentBeforeCandidateWasPublished() {
        when(experiments.find("experiment-1")).thenReturn(Optional.of(experiment()));
        when(observations.find("observation-1")).thenReturn(Optional.of(candidateObservation()));
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(List.of(new SkillVersion(
                "pkg-1", "skill-a", "1.1.0", "pending_review", "sha", 1, "artifact", "owner",
                OBSERVED_AT, null, null, "review-1")), List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> service.assess("experiment-1",
                new OptimizationExperimentAssessmentRequest("observation-1", "KEEP", ""), admin, "req-assess"))
                .isInstanceOf(OptimizationExperimentInvalidStateException.class)
                .hasMessageContaining("published");
    }

    private OptimizationExperiment experiment() {
        Instant time = OBSERVED_AT;
        OptimizationExperimentDecision decision = new OptimizationExperimentDecision(
                "decision-1", "experiment-1", "skill-a", "1.0.0", "1.1.0", "production", "", "", "",
                "smoke", "smoke-v1", "snapshot-1", "benchmark-1", QualityGateStatus.PASSED, "IMPROVED",
                OptimizationExperimentDecision.PROMOTE_CANDIDATE, "BENCHMARK_IMPROVED", "reason", "observe", "admin", time);
        return new OptimizationExperiment("experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0",
                "production", "", "", "", "smoke", "smoke-v1", OptimizationExperimentStatus.COMPLETED,
                "run-1", "snapshot-1", "benchmark-1", "", "admin", time, "admin", time, decision);
    }

    private OptimizationExperimentObservation candidateObservation() {
        return new OptimizationExperimentObservation("observation-1", "experiment-1", "skill-a", "1.1.0",
                "production", "", "", "", "24h", OBSERVED_AT, "admin", 5, 5, 0, 0, 0, 100, 80, "CAPTURED");
    }

    private RuntimeOperationsSnapshot baselineSnapshot() {
        return new RuntimeOperationsSnapshot("24h", OBSERVED_AT, "production", null,
                new RuntimeOperationsSnapshot.Totals(5, 5, 0, 0, 0, 95),
                new RuntimeOperationsSnapshot.Latency(5, 50, 100, 150), List.of(), List.of(), List.of(), List.of());
    }
}
