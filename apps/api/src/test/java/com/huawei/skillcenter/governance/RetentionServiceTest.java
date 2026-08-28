package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.events.GovernanceInvocationEventStore;
import com.huawei.skillcenter.events.InvocationEvent;
import com.huawei.skillcenter.events.InvocationEventStore;
import com.huawei.skillcenter.operations.RuntimeSummary;
import com.huawei.skillcenter.operations.RuntimeSummaryStore;
import com.huawei.skillcenter.quality.EvaluationRun;
import com.huawei.skillcenter.quality.EvaluationRunStatus;
import com.huawei.skillcenter.quality.EvaluationCaseResult;
import com.huawei.skillcenter.quality.RunnerExecutionStatus;
import com.huawei.skillcenter.quality.BenchmarkResult;
import com.huawei.skillcenter.quality.BenchmarkStore;
import com.huawei.skillcenter.quality.QualityEvidenceState;
import com.huawei.skillcenter.quality.QualityEvidenceStore;
import com.huawei.skillcenter.quality.QualityGateStatus;
import com.huawei.skillcenter.quality.QualityRuleSet;
import com.huawei.skillcenter.quality.QualitySnapshot;
import com.huawei.skillcenter.quality.SkillExecutionRecord;
import com.huawei.skillcenter.quality.CompatibilityMatrixCase;
import com.huawei.skillcenter.quality.CompatibilityMatrixCaseStatus;
import com.huawei.skillcenter.quality.CompatibilityMatrixPolicy;
import com.huawei.skillcenter.quality.CompatibilityMatrixRun;
import com.huawei.skillcenter.quality.CompatibilityMatrixStatus;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessment;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessmentStore;
import com.huawei.skillcenter.quality.OptimizationExperimentObservation;
import com.huawei.skillcenter.quality.OptimizationExperimentObservationStore;
import com.huawei.skillcenter.quality.SkillExecutionStore;
import com.huawei.skillcenter.execution.ExecutionEnvironmentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void previewsAndExecutesInvocationAndInstallationCleanupWhileKeepingAudits() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        events.putIfAbsent(event(Instant.now().minusSeconds(91L * 86_400L)));
        events.putIfAbsent(event(Instant.now().minusSeconds(2L * 86_400L)));
        store.addInstallation(installation(Instant.now().minusSeconds(91L * 86_400L)), null);
        store.addInstallation(installation(Instant.now().minusSeconds(2L * 86_400L)), null);
        store.addAudit(new AuditEvent("audit-1", "RETENTION_PREVIEW", "RETENTION", "policy", "admin", "admin",
                "req-1", Instant.now().minusSeconds(400L * 86_400L), java.util.Map.of()));

        RetentionService service = new RetentionService(store, events);
        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "req-preview");

        assertThat(preview.invocationEligibleCount()).isEqualTo(1);
        assertThat(preview.installationEligibleCount()).isEqualTo(1);
        assertThat(preview.auditArchiveEligibleCount()).isEqualTo(1);

        RetentionExecutionResult result = service.execute(new RetentionExecutionRequest(
                preview.previewId(), preview.policyVersion(), "batch-1"), new Actor("admin", "admin"), "req-execute");

        assertThat(result.invocationDeleted()).isEqualTo(1);
        assertThat(result.installationDeleted()).isEqualTo(1);
        assertThat(store.snapshot().invocationEvents()).hasSize(1);
        assertThat(store.snapshot().installations()).hasSize(1);
        assertThat(store.snapshot().audits()).extracting(AuditEvent::auditId).contains("audit-1");
    }

    @Test
    void reviewerCannotUpdateOrExecuteRetentionPolicy() {
        RetentionService service = new RetentionService(
                new GovernanceStore(tempDir.resolve("state.json"), List.of()),
                new GovernanceInvocationEventStore(new GovernanceStore(tempDir.resolve("state-2.json"), List.of())));

        assertThatThrownBy(() -> service.update(new RetentionPolicyMutation(1, 365, 90, 90),
                new Actor("reviewer", "reviewer"), "req-update"))
                .isInstanceOf(RetentionException.class)
                .hasMessageContaining("admin");
    }

    @Test
    void previewsAndDeletesExpiredRuntimeSummaries() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-runtime.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        RuntimeSummaryStore runtime = new RuntimeSummaryStore(tempDir.resolve("runtime.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        RuntimeSummary expired = new RuntimeSummary("1.0", UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(91), "skill-a", "1.0.0", "failure", 80,
                "E_DEP", "production", "team-a", "codex", "trace-a");
        runtime.putIfAbsent(expired);

        RetentionService service = new RetentionService(store, events, runtime);
        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "req-runtime-preview");

        assertThat(preview.runtimeSummaryEligibleCount()).isEqualTo(1);
        RetentionExecutionResult result = service.execute(new RetentionExecutionRequest(
                preview.previewId(), preview.policyVersion(), "runtime-batch"), new Actor("admin", "admin"), "req-runtime-execute");
        assertThat(result.runtimeSummaryDeleted()).isEqualTo(1);
        assertThat(runtime.findAll()).isEmpty();
    }

    @Test
    void previewsAndDeletesExpiredQualityEvidenceWithRuntimeData() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-quality.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        RuntimeSummaryStore runtime = new RuntimeSummaryStore(tempDir.resolve("runtime-quality.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        QualityEvidenceStore quality = new QualityEvidenceStore(tempDir.resolve("quality.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        Instant old = Instant.now().minusSeconds(91L * 86_400L);
        EvaluationRun run = new EvaluationRun("run-old", "skill-a", "1.0.0", "smoke", "smoke-v1",
                EvaluationRunStatus.COMPLETED, "mock-runner", "mock-evaluation", "mock", old.minusSeconds(10), old,
                2, 2, 100, "", QualityGateStatus.PASSED, List.of());
        QualitySnapshot snapshot = new QualitySnapshot("run-old", "skill-a", "1.0.0", "smoke", "smoke-v1",
                "mock-runner", "mock-evaluation", "mock", old, 100, 2, 2, true, "quality-v1", 100, 1.0,
                QualityGateStatus.PASSED, List.of());
        quality.save(new QualityEvidenceState(List.of(), new QualityRuleSet("default", "quality-v1", 80, 0.8, 90),
                List.of(run), List.of(snapshot)));

        RetentionService service = new RetentionService(store, events, runtime, quality);
        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "req-quality-preview");
        assertThat(preview.qualityEvidenceEligibleCount()).isEqualTo(2);

        RetentionExecutionResult result = service.execute(new RetentionExecutionRequest(
                preview.previewId(), preview.policyVersion(), "quality-batch"), new Actor("admin", "admin"), "req-quality-execute");
        assertThat(result.qualityEvidenceDeleted()).isEqualTo(2);
        assertThat(quality.load().runs()).isEmpty();
        assertThat(quality.load().snapshots()).isEmpty();
    }

    @Test
    void cascadesExpiredEvaluationRunsToNewerSnapshotsAndCaseResults() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-quality-cascade.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        QualityEvidenceStore quality = new QualityEvidenceStore(tempDir.resolve("quality-cascade.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        Instant old = Instant.now().minusSeconds(91L * 86_400L);
        Instant newer = Instant.now().minusSeconds(2L * 86_400L);
        EvaluationRun run = new EvaluationRun("run-cascade", "skill-a", "1.0.0", "smoke", "smoke-v1",
                EvaluationRunStatus.COMPLETED, "mock-runner", "mock-evaluation", "mock", old.minusSeconds(10), old,
                1, 1, 100, "", QualityGateStatus.PASSED, List.of());
        QualitySnapshot snapshot = new QualitySnapshot("run-cascade", "skill-a", "1.0.0", "smoke", "smoke-v1",
                "mock-runner", "mock-evaluation", "mock", newer, 100, 1, 1, true, "quality-v1", 100, 1.0,
                QualityGateStatus.PASSED, List.of());
        EvaluationCaseResult caseResult = new EvaluationCaseResult("run-cascade", "case-1", "Case 1",
                RunnerExecutionStatus.SUCCEEDED, true, 100, 10, "", "", "mock", newer);
        quality.save(new QualityEvidenceState(List.of(), new QualityRuleSet("default", "quality-v1", 80, 0.8, 90),
                List.of(run), List.of(snapshot), List.of(caseResult)));

        RetentionService service = new RetentionService(store, events, new RuntimeSummaryStore(), quality);
        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "req-quality-cascade-preview");
        assertThat(preview.qualityEvidenceEligibleCount()).isEqualTo(3);

        RetentionExecutionResult result = service.execute(new RetentionExecutionRequest(
                preview.previewId(), preview.policyVersion(), "quality-cascade-batch"), new Actor("admin", "admin"),
                "req-quality-cascade-execute");
        assertThat(result.qualityEvidenceDeleted()).isEqualTo(3);
        assertThat(quality.load().runs()).isEmpty();
        assertThat(quality.load().snapshots()).isEmpty();
        assertThat(quality.load().caseResults()).isEmpty();
    }

    @Test
    void retentionPreservesActiveCompatibilityMatricesWhenOldEvaluationEvidenceExpires() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-matrix-retention.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        QualityEvidenceStore quality = new QualityEvidenceStore(tempDir.resolve("matrix-retention.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        Instant old = Instant.now().minusSeconds(91L * 86_400L);
        EvaluationRun oldRun = new EvaluationRun("run-old-matrix", "skill-a", "1.0.0", "smoke", "smoke-v1",
                EvaluationRunStatus.COMPLETED, "mock-runner", "mock-evaluation", "mock", old.minusSeconds(10), old,
                1, 1, 100, "", QualityGateStatus.PASSED, List.of());
        String matrixId = UUID.randomUUID().toString();
        CompatibilityMatrixRun matrix = new CompatibilityMatrixRun(matrixId, "skill-a", "1.0.0", "smoke", "smoke-v1",
                CompatibilityMatrixPolicy.ALL_MUST_PASS, 1, false, CompatibilityMatrixStatus.RUNNING, "mock", "success",
                1_000, 1, 0, 0, 0, 0, QualityGateStatus.BLOCKED, List.of("EVALUATION_NOT_COMPLETED"),
                "admin", Instant.now(), null);
        CompatibilityMatrixCase matrixCase = new CompatibilityMatrixCase("case-matrix-retention", matrixId, "",
                "openclaw", "", "", "context-v1", "", "", ExecutionEnvironmentStatus.ACTIVE, null, null,
                CompatibilityMatrixCaseStatus.QUEUED, 0, QualityGateStatus.BLOCKED,
                List.of("EVALUATION_NOT_COMPLETED"), "", null, null);
        quality.save(new QualityEvidenceState(List.of(), new QualityRuleSet("default", "quality-v1", 80, 0.8, 90),
                List.of(oldRun), List.of(), List.of(), List.of(matrix), List.of(matrixCase)));

        RetentionService service = new RetentionService(store, events, new RuntimeSummaryStore(), quality);
        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "req-matrix-retention-preview");
        assertThat(preview.qualityEvidenceEligibleCount()).isEqualTo(1);
        assertThat(preview.compatibilityMatrixEligibleCount()).isZero();

        RetentionExecutionResult result = service.execute(new RetentionExecutionRequest(preview.previewId(), preview.policyVersion(),
                "matrix-retention-batch"), new Actor("admin", "admin"), "req-matrix-retention-execute");

        assertThat(result.compatibilityMatrixDeleted()).isZero();
        assertThat(quality.load().runs()).isEmpty();
        assertThat(quality.load().matrixRuns()).singleElement().extracting(CompatibilityMatrixRun::matrixRunId)
                .isEqualTo(matrixId);
        assertThat(quality.load().matrixCases()).singleElement().extracting(CompatibilityMatrixCase::matrixRunId)
                .isEqualTo(matrixId);
    }

    @Test
    void previewsAndDeletesExpiredBenchmarksAlongsideQualityEvidence() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-benchmark-retention.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        BenchmarkStore benchmarks = new BenchmarkStore(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                tempDir.resolve("benchmarks-retention.json").toString());
        Instant old = Instant.now().minusSeconds(91L * 86_400L);
        benchmarks.add(new BenchmarkResult("benchmark-old", "skill-a", "1.0.0", "1.1.0", "24h", "mock",
                "REGRESSED", old, null));

        RetentionService service = new RetentionService(store, events, new RuntimeSummaryStore(),
                new QualityEvidenceStore(), benchmarks);
        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "req-benchmark-retention-preview");
        assertThat(preview.benchmarkEligibleCount()).isEqualTo(1);

        RetentionExecutionResult result = service.execute(new RetentionExecutionRequest(
                preview.previewId(), preview.policyVersion(), "benchmark-retention-batch"), new Actor("admin", "admin"),
                "req-benchmark-retention-execute");
        assertThat(result.benchmarkDeleted()).isEqualTo(1);
        assertThat(benchmarks.findAll("skill-a")).isEmpty();
    }

    @Test
    void previewsAndDeletesExpiredRunnerExecutions() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-runner-retention.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        com.huawei.skillcenter.quality.SkillExecutionStore executions = new com.huawei.skillcenter.quality.SkillExecutionStore(
                mapper, tempDir.resolve("runner-executions.json").toString());
        Instant old = Instant.now().minusSeconds(91L * 86_400L);
        executions.save(new SkillExecutionRecord(UUID.randomUUID(), "skill-a", "1.0.0",
                RunnerExecutionStatus.SUCCEEDED, "mock-runner", "1.0", "mock", 42, "hash", "", old));

        RetentionService service = new RetentionService(store, events, new RuntimeSummaryStore(),
                new QualityEvidenceStore(), null, executions);
        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "req-runner-retention-preview");
        assertThat(preview.runnerExecutionEligibleCount()).isEqualTo(1);

        RetentionExecutionResult result = service.execute(new RetentionExecutionRequest(
                preview.previewId(), preview.policyVersion(), "runner-retention-batch"),
                new Actor("admin", "admin"), "req-runner-retention-execute");
        assertThat(result.runnerExecutionDeleted()).isEqualTo(1);
        assertThat(executions.findAll("skill-a")).isEmpty();
    }

    @Test
    void includesPostReleaseObservationsAndAssessmentsInQualityEvidenceRetention() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-post-release-retention.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        OptimizationExperimentObservationStore observations = new OptimizationExperimentObservationStore(
                mapper, tempDir.resolve("observations.json").toString());
        OptimizationExperimentAssessmentStore assessments = new OptimizationExperimentAssessmentStore(
                mapper, tempDir.resolve("assessments.json").toString());
        Instant old = Instant.now().minusSeconds(91L * 86_400L);
        observations.create(new OptimizationExperimentObservation("observation-old", "experiment-1", "skill-a",
                "1.1.0", "production", "", "", "", "24h", old, "admin", 5, 5, 0, 0, 0,
                100, 80, "CAPTURED"));
        var metrics = new OptimizationExperimentAssessment.Metrics(5, 5, 0, 0, 0, 100, 80, old);
        assessments.create(new OptimizationExperimentAssessment("assessment-old", "experiment-1", "work-1",
                "skill-a", "1.0.0", "1.1.0", "production", "", "", "", "24h", "observation-old",
                metrics, metrics, 95, 1_000, 5, OptimizationExperimentAssessment.HEALTHY,
                "POST_RELEASE_HEALTHY", OptimizationExperimentAssessment.KEEP, OptimizationExperimentAssessment.KEEP,
                "", "admin", old));

        RetentionService service = new RetentionService(store, events, new RuntimeSummaryStore(),
                new QualityEvidenceStore(), null, new SkillExecutionStore(), observations, assessments);
        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "req-post-release-retention-preview");
        assertThat(preview.qualityEvidenceEligibleCount()).isEqualTo(2);

        RetentionExecutionResult result = service.execute(new RetentionExecutionRequest(
                preview.previewId(), preview.policyVersion(), "post-release-retention-batch"),
                new Actor("admin", "admin"), "req-post-release-retention-execute");
        assertThat(result.qualityEvidenceDeleted()).isEqualTo(2);
        assertThat(observations.findAll("")).isEmpty();
        assertThat(assessments.findAll("")).isEmpty();
    }

    @Test
    void preservesExpiredQualityEvidenceAndBenchmarkReferencedByLifecycleAssets() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-protected-retention.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        QualityEvidenceStore quality = new QualityEvidenceStore();
        Instant old = Instant.now().minusSeconds(91L * 86_400L);
        EvaluationRun run = new EvaluationRun("run-protected", "skill-a", "1.0.0", "smoke", "smoke-v1",
                EvaluationRunStatus.COMPLETED, "mock-runner", "mock-evaluation", "mock", old.minusSeconds(10), old,
                1, 1, 100, "", QualityGateStatus.PASSED, List.of());
        QualitySnapshot snapshot = new QualitySnapshot("run-protected", "skill-a", "1.0.0", "smoke", "smoke-v1",
                "mock-runner", "mock-evaluation", "mock", old, 100, 1, 1, true, "quality-v1", 100, 1.0,
                QualityGateStatus.PASSED, List.of());
        quality.save(new QualityEvidenceState(List.of(), null, List.of(run), List.of(snapshot)));
        BenchmarkStore benchmarks = new BenchmarkStore(new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules(), tempDir.resolve("protected-benchmarks.json").toString());
        benchmarks.add(new BenchmarkResult("benchmark-protected", "skill-a", "1.0.0", "1.1.0", "24h", "mock",
                "IMPROVED", old, null));
        RetentionProtectionSnapshot protection = RetentionProtectionSnapshot.from(List.of(
                new RetentionEvidenceReference("EXPERIMENT", "experiment-1", "EVALUATION_RUN", "run-protected"),
                new RetentionEvidenceReference("RELEASE", "release-1", "QUALITY_SNAPSHOT", "run-protected"),
                new RetentionEvidenceReference("EXPERIMENT", "experiment-1", "BENCHMARK", "benchmark-protected")));
        RetentionService service = new RetentionService(store, events, new RuntimeSummaryStore(), quality, benchmarks,
                new SkillExecutionStore(), null, null, () -> protection);

        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "protected-preview");

        assertThat(preview.qualityEvidenceEligibleCount()).isZero();
        assertThat(preview.benchmarkEligibleCount()).isZero();
        assertThat(preview.protectedEvaluationRunCount()).isEqualTo(1);
        assertThat(preview.protectedQualitySnapshotCount()).isEqualTo(1);
        assertThat(preview.protectedBenchmarkCount()).isEqualTo(1);
        RetentionExecutionResult result = service.execute(new RetentionExecutionRequest(
                preview.previewId(), preview.policyVersion(), "protected-execute"),
                new Actor("admin", "admin"), "protected-execute-request");

        assertThat(result.qualityEvidenceDeleted()).isZero();
        assertThat(result.benchmarkDeleted()).isZero();
        assertThat(quality.load().runs()).extracting(EvaluationRun::id).containsExactly("run-protected");
        assertThat(benchmarks.findAll("skill-a")).extracting(BenchmarkResult::benchmarkId)
                .containsExactly("benchmark-protected");
    }

    @Test
    void rejectsRetentionExecutionWhenEvidenceReferencesChangeAfterPreview() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-protection-conflict.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        BenchmarkStore benchmarks = new BenchmarkStore(new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules(), tempDir.resolve("conflict-benchmarks.json").toString());
        Instant old = Instant.now().minusSeconds(91L * 86_400L);
        benchmarks.add(new BenchmarkResult("benchmark-old", "skill-a", "1.0.0", "1.1.0", "24h", "mock",
                "REGRESSED", old, null));
        RetentionProtectionSnapshot empty = RetentionProtectionSnapshot.empty();
        RetentionProtectionSnapshot changed = RetentionProtectionSnapshot.from(List.of(
                new RetentionEvidenceReference("EXPERIMENT", "experiment-1", "BENCHMARK", "benchmark-old")));
        AtomicInteger calls = new AtomicInteger();
        RetentionService service = new RetentionService(store, events, new RuntimeSummaryStore(),
                new QualityEvidenceStore(), benchmarks, new SkillExecutionStore(), null, null,
                () -> calls.getAndIncrement() == 0 ? empty : changed);
        RetentionPreview preview = service.preview(new Actor("admin", "admin"), "conflict-preview");

        assertThatThrownBy(() -> service.execute(new RetentionExecutionRequest(
                preview.previewId(), preview.policyVersion(), "conflict-execute"),
                new Actor("admin", "admin"), "conflict-request"))
                .isInstanceOf(RetentionException.class)
                .extracting(exception -> ((RetentionException) exception).code())
                .isEqualTo("RETENTION_PROTECTION_CONFLICT");
        assertThat(benchmarks.findAll("skill-a")).extracting(BenchmarkResult::benchmarkId)
                .containsExactly("benchmark-old");
    }

    @Test
    void failsClosedWhenEvidenceReferenceIndexIsUnavailable() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-protection-unavailable.json"), List.of());
        InvocationEventStore events = new GovernanceInvocationEventStore(store);
        RetentionService service = new RetentionService(store, events, new RuntimeSummaryStore(),
                new QualityEvidenceStore(), null, new SkillExecutionStore(), null, null,
                () -> { throw new IllegalStateException("reference storage unavailable"); });

        assertThatThrownBy(() -> service.preview(new Actor("admin", "admin"), "unavailable-preview"))
                .isInstanceOf(RetentionException.class)
                .extracting(exception -> ((RetentionException) exception).code())
                .isEqualTo("RETENTION_EVIDENCE_PROTECTION_UNAVAILABLE");
    }

    private InvocationEvent event(Instant occurredAt) {
        return new InvocationEvent("1.0", UUID.randomUUID(), occurredAt.atOffset(ZoneOffset.UTC), "eox-query", "1.2.0",
                new InvocationEvent.Subject("alice", "team-a"), new InvocationEvent.Client("codex", "1.0.0"),
                "session_1234567890", "success", 42, null, null);
    }

    private InstallationRecord installation(Instant requestedAt) {
        return new InstallationRecord("i-" + UUID.randomUUID(), "m1", "eox-query", "1.2.0", "codex", "1.0.0",
                "alice", "installed", requestedAt, requestedAt.plusSeconds(60));
    }
}
