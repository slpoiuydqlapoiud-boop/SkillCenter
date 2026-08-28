package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptimizationWorkItemServiceTest {
    @TempDir
    Path tempDir;

    private OptimizationWorkItemStore store;
    private OptimizationSuggestionService suggestions;
    private QualityEvaluationService evaluations;
    private BenchmarkService benchmarks;
    private OptimizationExperimentAssessmentStore assessments;
    private GovernanceStore governance;
    private OptimizationWorkItemService service;

    @BeforeEach
    void setUp() {
        store = new OptimizationWorkItemStore(tempDir.resolve("work-items.json"),
                new ObjectMapper().findAndRegisterModules());
        suggestions = mock(OptimizationSuggestionService.class);
        evaluations = mock(QualityEvaluationService.class);
        benchmarks = mock(BenchmarkService.class);
        assessments = mock(OptimizationExperimentAssessmentStore.class);
        governance = mock(GovernanceStore.class);
        SkillVersion candidate = new SkillVersion("pkg-1", "skill-a", "1.1.0", "published", "sha", 1,
                "artifact", "owner", Instant.parse("2026-08-24T00:00:00Z"), "admin",
                Instant.parse("2026-08-24T00:00:00Z"), "review-1");
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(List.of(candidate), List.of(), List.of(), List.of()));
        when(governance.addAudit(any())).thenReturn(GovernanceSnapshot.empty());
        service = new OptimizationWorkItemService(store, suggestions, evaluations, benchmarks, governance, assessments);
    }

    @Test
    void createsFromSuggestionAndSnapshotsSafeContext() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));

        OptimizationWorkItem created = service.create(new OptimizationWorkItemCreateRequest(
                "skill-a", "1.0.0", "runtime-latency", "尝试降低外部依赖延迟", "owner-a",
                "production", "runtime-a", "mcp-a", "llm-a"), new Actor("admin", "admin"), "req-1");

        assertThat(created.status()).isEqualTo(OptimizationWorkItemStatus.OPEN);
        assertThat(created.ownerId()).isEqualTo("owner-a");
        assertThat(created.suggestionEvidence()).containsExactly("p95Ms=1200");
        assertThat(created.runtimeId()).isEqualTo("runtime-a");
    }

    @Test
    void createsAnIdempotentFollowUpWorkItemFromPostReleaseAssessment() {
        Actor actor = new Actor("admin", "admin");
        OptimizationExperimentAssessment assessment = postReleaseAssessment("assessment-1", "1.1.0");

        OptimizationWorkItem first = service.createPostReleaseFollowUp(assessment, actor, "req-follow-up");
        OptimizationWorkItem second = service.createPostReleaseFollowUp(assessment, actor, "req-follow-up-retry");

        assertThat(second).isEqualTo(first);
        assertThat(first.sourceVersion()).isEqualTo("1.1.0");
        assertThat(first.suggestionId()).isEqualTo("post-release-assessment:assessment-1");
        assertThat(first.suggestionEvidence()).contains("postReleaseAssessmentId=assessment-1");
        assertThat(first.evidenceType()).isEqualTo(OptimizationWorkItem.NONE);
        assertThat(store.findAll("skill-a", "", "", "1.1.0")).hasSize(1);
    }

    @Test
    void concurrentFollowUpRetriesReturnOneWorkItemAndOneCreationAudit() throws Exception {
        CoordinatedWorkItemStore coordinatedStore = new CoordinatedWorkItemStore(
                tempDir.resolve("concurrent-work-items.json"), new ObjectMapper().findAndRegisterModules());
        service = new OptimizationWorkItemService(coordinatedStore, suggestions, evaluations, benchmarks,
                governance, assessments);
        Actor actor = new Actor("admin", "admin");
        OptimizationExperimentAssessment assessment = postReleaseAssessment("assessment-concurrent", "1.1.0");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> service.createPostReleaseFollowUp(assessment, actor, "req-1"));
            var second = executor.submit(() -> service.createPostReleaseFollowUp(assessment, actor, "req-2"));

            OptimizationWorkItem firstItem = first.get(5, TimeUnit.SECONDS);
            OptimizationWorkItem secondItem = second.get(5, TimeUnit.SECONDS);

            assertThat(secondItem).isEqualTo(firstItem);
            assertThat(coordinatedStore.findAll("skill-a", "", "", "1.1.0")).hasSize(1);
            verify(governance, times(1)).addAudit(any());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void transitionsThroughEvaluationAndCompletesWithBenchmarkEvidence() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        when(benchmarks.list("skill-a", "production", "runtime-a", "mcp-a", "llm-a"))
                .thenReturn(List.of(new BenchmarkResult("benchmark-1", "skill-a", "1.0.0", "1.1.0",
                        "24h", "production", "IMPROVED", Instant.now(), null,
                        "runtime-a", "mcp-a", "llm-a")));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem item = service.create(createRequest(), actor, "req-1");

        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("PLANNED", "", ""), actor, "req-2");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("IN_PROGRESS", "", ""), actor, "req-3");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("READY_FOR_EVALUATION", "1.1.0", ""), actor, "req-4");
        item = service.bindEvidence(item.workItemId(), new OptimizationWorkItemEvidenceRequest("BENCHMARK", "benchmark-1", ""), actor, "req-5");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("COMPLETED", "", "延迟改善已验证"), actor, "req-6");

        assertThat(item.status()).isEqualTo(OptimizationWorkItemStatus.COMPLETED);
        assertThat(item.candidateVersion()).isEqualTo("1.1.0");
        assertThat(item.evidenceId()).isEqualTo("benchmark-1");
    }

    @Test
    void rejectsInvalidTransitionsAndCompletionWithoutEvidence() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem item = service.create(createRequest(), actor, "req-1");

        assertThatThrownBy(() -> service.transition(item.workItemId(),
                new OptimizationWorkItemStatusRequest("COMPLETED", "1.1.0", "未验证"), actor, "req-2"))
                .isInstanceOf(OptimizationWorkItemInvalidStateException.class);
        assertThatThrownBy(() -> service.transition(item.workItemId(),
                new OptimizationWorkItemStatusRequest("IN_PROGRESS", "", ""), new Actor("developer", "developer"), "req-3"))
                .isInstanceOf(com.huawei.skillcenter.governance.ForbiddenException.class);
    }

    @Test
    void rejectsEvidenceThatDoesNotMatchCandidateContext() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        when(benchmarks.list("skill-a", "production", "runtime-a", "mcp-a", "llm-a"))
                .thenReturn(List.of(new BenchmarkResult("benchmark-2", "skill-a", "1.0.0", "9.9.9",
                        "24h", "production", "IMPROVED", Instant.now(), null,
                        "runtime-a", "mcp-a", "llm-a")));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem item = service.create(createRequest(), actor, "req-1");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("PLANNED", "", ""), actor, "req-2");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("IN_PROGRESS", "", ""), actor, "req-3");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("READY_FOR_EVALUATION", "1.1.0", ""), actor, "req-4");
        OptimizationWorkItem readyItem = item;

        assertThatThrownBy(() -> service.bindEvidence(readyItem.workItemId(),
                new OptimizationWorkItemEvidenceRequest("BENCHMARK", "benchmark-2", ""), actor, "req-3"))
                .isInstanceOf(OptimizationWorkItemEvidenceException.class);
    }

    @Test
    void createsAndPreservesPinnedSuiteContextAcrossLifecycle() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        Actor actor = new Actor("admin", "admin");

        OptimizationWorkItem item = service.create(pinnedCreateRequest(), actor, "req-1");
        assertThat(item.suiteId()).isEqualTo("suite-a");
        assertThat(item.suiteVersion()).isEqualTo("suite-v1");

        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("PLANNED", "", ""), actor, "req-2");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("IN_PROGRESS", "", ""), actor, "req-3");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("ABANDONED", "", "暂缓"), actor, "req-4");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("OPEN", "", ""), actor, "req-5");

        assertThat(item.suiteId()).isEqualTo("suite-a");
        assertThat(item.suiteVersion()).isEqualTo("suite-v1");
    }

    @Test
    void rejectsEvaluationRunFromAnotherSuiteVersion() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        when(evaluations.find("run-other-suite")).thenReturn(evaluationRun("suite-a", "suite-v2"));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem ready = readyPinnedItem(actor);

        assertThatThrownBy(() -> service.bindEvidence(ready.workItemId(),
                new OptimizationWorkItemEvidenceRequest("EVALUATION_RUN", "run-other-suite", ""), actor, "req-5"))
                .isInstanceOf(OptimizationWorkItemEvidenceException.class)
                .hasMessage("evaluation evidence does not match candidate context");
    }

    @Test
    void bindsEvaluationRunWithSameSuiteVersion() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        when(evaluations.find("run-same-suite")).thenReturn(evaluationRun("suite-a", "suite-v1"));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem ready = readyPinnedItem(actor);

        OptimizationWorkItem bound = service.bindEvidence(ready.workItemId(),
                new OptimizationWorkItemEvidenceRequest("EVALUATION_RUN", "run-same-suite", "已验证"), actor, "req-5");

        assertThat(bound.evidenceType()).isEqualTo(OptimizationWorkItem.EVALUATION_RUN);
        assertThat(bound.evidenceId()).isEqualTo("run-same-suite");
    }

    @Test
    void rejectsQualitySnapshotFromAnotherSuite() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        when(evaluations.findSnapshot("snapshot-other-suite"))
                .thenReturn(snapshot("suite-b", "suite-v1"));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem ready = readyPinnedItem(actor);

        assertThatThrownBy(() -> service.bindEvidence(ready.workItemId(),
                new OptimizationWorkItemEvidenceRequest("QUALITY_SNAPSHOT", "snapshot-other-suite", ""), actor, "req-5"))
                .isInstanceOf(OptimizationWorkItemEvidenceException.class)
                .hasMessage("quality snapshot does not match candidate context");
    }

    @Test
    void bindsQualitySnapshotWithSameSuiteVersion() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        when(evaluations.findSnapshot("snapshot-same-suite"))
                .thenReturn(snapshot("suite-a", "suite-v1"));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem ready = readyPinnedItem(actor);

        OptimizationWorkItem bound = service.bindEvidence(ready.workItemId(),
                new OptimizationWorkItemEvidenceRequest("QUALITY_SNAPSHOT", "snapshot-same-suite", "已验证"), actor, "req-5");

        assertThat(bound.evidenceType()).isEqualTo(OptimizationWorkItem.QUALITY_SNAPSHOT);
        assertThat(bound.evidenceId()).isEqualTo("snapshot-same-suite");
    }

    @Test
    void rejectsBenchmarkFromAnotherSuiteVersion() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        when(benchmarks.list("skill-a", "production", "runtime-a", "mcp-a", "llm-a", "suite-a", "suite-v1"))
                .thenReturn(List.of(new BenchmarkResult("benchmark-other-suite", "skill-a", "1.0.0", "1.1.0",
                        "24h", "production", "IMPROVED", Instant.now(), null,
                        "runtime-a", "mcp-a", "llm-a", "suite-a", "suite-v2")));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem ready = readyPinnedItem(actor);

        assertThatThrownBy(() -> service.bindEvidence(ready.workItemId(),
                new OptimizationWorkItemEvidenceRequest("BENCHMARK", "benchmark-other-suite", ""), actor, "req-5"))
                .isInstanceOf(OptimizationWorkItemEvidenceException.class)
                .hasMessage("benchmark evidence does not match candidate context");
    }

    @Test
    void bindsBenchmarkWithSameSuiteVersion() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        when(benchmarks.list("skill-a", "production", "runtime-a", "mcp-a", "llm-a", "suite-a", "suite-v1"))
                .thenReturn(List.of(new BenchmarkResult("benchmark-same-suite", "skill-a", "1.0.0", "1.1.0",
                        "24h", "production", "IMPROVED", Instant.now(), null,
                        "runtime-a", "mcp-a", "llm-a", "suite-a", "suite-v1")));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem ready = readyPinnedItem(actor);

        OptimizationWorkItem bound = service.bindEvidence(ready.workItemId(),
                new OptimizationWorkItemEvidenceRequest("BENCHMARK", "benchmark-same-suite", "已验证"), actor, "req-5");

        assertThat(bound.evidenceType()).isEqualTo(OptimizationWorkItem.BENCHMARK);
        assertThat(bound.evidenceId()).isEqualTo("benchmark-same-suite");
    }

    @Test
    void bindsPostReleaseAssessmentWithSameCandidateAndEnvironmentContext() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        when(assessments.find("assessment-1")).thenReturn(Optional.of(postReleaseAssessment("assessment-1", "1.1.0")));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem ready = readyPinnedItem(actor);

        OptimizationWorkItem bound = service.bindEvidence(ready.workItemId(),
                new OptimizationWorkItemEvidenceRequest(OptimizationWorkItem.POST_RELEASE_ASSESSMENT, "assessment-1", "已完成发布后评估"),
                actor, "req-5");

        assertThat(bound.evidenceType()).isEqualTo(OptimizationWorkItem.POST_RELEASE_ASSESSMENT);
        assertThat(bound.evidenceId()).isEqualTo("assessment-1");
    }

    @Test
    void rejectsPostReleaseAssessmentFromAnotherCandidateVersion() {
        when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(suggestion()));
        when(assessments.find("assessment-other")).thenReturn(Optional.of(postReleaseAssessment("assessment-other", "9.9.9")));
        Actor actor = new Actor("admin", "admin");
        OptimizationWorkItem ready = readyPinnedItem(actor);

        assertThatThrownBy(() -> service.bindEvidence(ready.workItemId(),
                new OptimizationWorkItemEvidenceRequest(OptimizationWorkItem.POST_RELEASE_ASSESSMENT, "assessment-other", ""),
                actor, "req-5"))
                .isInstanceOf(OptimizationWorkItemEvidenceException.class)
                .hasMessage("post-release assessment does not match candidate context");
    }

    private OptimizationWorkItem readyPinnedItem(Actor actor) {
        OptimizationWorkItem item = service.create(pinnedCreateRequest(), actor, "req-1");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("PLANNED", "", ""), actor, "req-2");
        item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("IN_PROGRESS", "", ""), actor, "req-3");
        return service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("READY_FOR_EVALUATION", "1.1.0", ""), actor, "req-4");
    }

    private OptimizationWorkItemCreateRequest pinnedCreateRequest() {
        return new OptimizationWorkItemCreateRequest("skill-a", "1.0.0", "runtime-latency",
                "尝试降低外部依赖延迟", "owner-a", "production", "runtime-a", "mcp-a", "llm-a",
                "suite-a", "suite-v1");
    }

    private static final class CoordinatedWorkItemStore extends OptimizationWorkItemStore {
        private final CountDownLatch concurrentCreators = new CountDownLatch(2);

        private CoordinatedWorkItemStore(Path statePath, ObjectMapper objectMapper) {
            super(statePath, objectMapper);
        }

        @Override
        public OptimizationWorkItem create(OptimizationWorkItem value) {
            concurrentCreators.countDown();
            try {
                if (!concurrentCreators.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("both concurrent requests must reach the atomic create");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("coordinated store was interrupted", interrupted);
            }
            return super.create(value);
        }
    }

    private EvaluationRun evaluationRun(String suiteId, String suiteVersion) {
        Instant now = Instant.parse("2026-08-24T01:02:03Z");
        return new EvaluationRun("run-1", "skill-a", "1.1.0", suiteId, suiteVersion,
                EvaluationRunStatus.COMPLETED, "runner", "provider", "production", now, now,
                1, 1, 100, "", QualityGateStatus.PASSED, List.of(), "runtime-a", "mcp-a", "llm-a");
    }

    private QualitySnapshot snapshot(String suiteId, String suiteVersion) {
        return new QualitySnapshot("snapshot-1", "skill-a", "1.1.0", suiteId, suiteVersion,
                "runner", "provider", "production", Instant.parse("2026-08-24T01:02:03Z"),
                100, 1, 1, true, "rules-v1", 100, 1.0, QualityGateStatus.PASSED, List.of(),
                "runtime-a", "mcp-a", "llm-a");
    }

    private OptimizationWorkItemCreateRequest createRequest() {
        return new OptimizationWorkItemCreateRequest("skill-a", "1.0.0", "runtime-latency",
                "尝试降低外部依赖延迟", "owner-a", "production", "runtime-a", "mcp-a", "llm-a");
    }

    private OptimizationSuggestion suggestion() {
        return new OptimizationSuggestion("runtime-latency", "skill-a", "1.0.0", "MEDIUM", "LATENCY",
                "运行 P95 延迟偏高", List.of("p95Ms=1200"), "检查外部依赖");
    }

    private OptimizationExperimentAssessment postReleaseAssessment(String id, String candidateVersion) {
        Instant time = Instant.parse("2026-08-24T03:00:00Z");
        OptimizationExperimentAssessment.Metrics metrics = new OptimizationExperimentAssessment.Metrics(
                5, 5, 0, 0, 0, 100, 80, time);
        return new OptimizationExperimentAssessment(id, "experiment-1", "work-1", "skill-a", "1.0.0",
                candidateVersion, "production", "runtime-a", "mcp-a", "llm-a", "suite-a", "suite-v1", "24h", "observation-1",
                metrics, metrics, 95, 1_000, 5, OptimizationExperimentAssessment.HEALTHY,
                "POST_RELEASE_HEALTHY", OptimizationExperimentAssessment.KEEP, OptimizationExperimentAssessment.KEEP,
                "", "admin", time);
    }
}
