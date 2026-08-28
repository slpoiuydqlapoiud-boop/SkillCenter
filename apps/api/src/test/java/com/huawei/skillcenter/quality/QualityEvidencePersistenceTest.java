package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityEvidencePersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void qualityRunsAndSnapshotsReloadAfterServiceRecreation() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        QualityEvidenceStore firstStore = new QualityEvidenceStore(tempDir.resolve("quality.json"), mapper);
        QualityEvaluationService first = new QualityEvaluationService(
                new MockRunner(), new MockEvaluationProvider(), Clock.fixed(
                        Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC), firstStore);

        EvaluationRun submitted = first.submit(new EvaluationRequest("eox-query", "1.2.0", "smoke", "success", 1_000));
        EvaluationRun completed = awaitCompleted(first, submitted.id());
        assertThat(completed.status()).isEqualTo(EvaluationRunStatus.COMPLETED);

        QualityEvidenceStore restoredStore = new QualityEvidenceStore(tempDir.resolve("quality.json"), mapper);
        QualityEvaluationService restored = new QualityEvaluationService(
                new MockRunner(), new MockEvaluationProvider(), Clock.systemUTC(), restoredStore);

        assertThat(restored.find(completed.id())).isEqualTo(completed);
        assertThat(restored.snapshot(completed.id())).isNotNull();
        assertThat(restored.snapshots("eox-query")).hasSize(1);
        assertThat(restored.results(completed.id())).hasSize(2)
                .allSatisfy(result -> assertThat(result.dataSource()).isEqualTo("mock"));
    }

    @Test
    void providerExceptionBecomesPersistedFailedRunWithoutLeakingExceptionText() {
        QualityEvaluationService service = new QualityEvaluationService(
                new ThrowingRunner(), new MockEvaluationProvider(), Clock.systemUTC(), new QualityEvidenceStore());
        EvaluationRun submitted = service.submit(new EvaluationRequest("eox-query", "1.2.0", "smoke", "success", 1_000));

        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        EvaluationRun current;
        do {
            current = service.find(submitted.id());
            if (current.status() == EvaluationRunStatus.FAILED) break;
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        } while (System.nanoTime() < deadline);

        assertThat(current.status()).isEqualTo(EvaluationRunStatus.FAILED);
        assertThat(current.errorCode()).isEqualTo("EVALUATION_EXECUTION_FAILED");
        assertThat(current.errorCode()).doesNotContain("provider-secret");
    }

    @Test
    void rejectsSemanticallyInvalidPersistedQualityEvidence() throws Exception {
        Path path = tempDir.resolve("quality.json");
        Files.writeString(path, "{\"suites\":[],\"rules\":null,\"runs\":[{\"status\":\"COMPLETED\",\"gateReasons\":[]}],\"snapshots\":[],\"caseResults\":[]}");

        assertThatThrownBy(() -> new QualityEvidenceStore(path, new ObjectMapper().findAndRegisterModules()))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsSemanticallyInvalidQualityEvidenceBeforeWriting() {
        QualityEvidenceStore store = new QualityEvidenceStore(tempDir.resolve("quality-write.json"),
                new ObjectMapper().findAndRegisterModules());
        EvaluationRun invalid = new EvaluationRun(null, "skill-a", "1.0.0", "smoke", "1.0",
                EvaluationRunStatus.COMPLETED, "runner", "provider", "mock", Instant.now(), Instant.now(),
                1, 1, 100, null, QualityGateStatus.PASSED, java.util.List.of());

        assertThatThrownBy(() -> store.save(new QualityEvidenceState(java.util.List.of(), null,
                        java.util.List.of(invalid), java.util.List.of(), java.util.List.of())))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsDuplicateEvaluationRunIdsDuringStartup() throws Exception {
        EvaluationRun run = new EvaluationRun("run-1", "skill-a", "1.0.0", "smoke", "1.0",
                EvaluationRunStatus.COMPLETED, "runner", "provider", "mock", Instant.now(), Instant.now(),
                1, 1, 100, "", QualityGateStatus.PASSED, java.util.List.of());
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(run, run), java.util.List.of(), java.util.List.of());
        Path path = tempDir.resolve("quality-duplicate.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Files.writeString(path, mapper.writeValueAsString(state));

        assertThatThrownBy(() -> new QualityEvidenceStore(path, mapper))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsDuplicateCaseResultsForTheSameRunDuringStartup() throws Exception {
        EvaluationCaseResult result = new EvaluationCaseResult("run-1", "case-1", "case-1",
                RunnerExecutionStatus.SUCCEEDED, true, 100, 10, "", "passed", "mock", Instant.now());
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(result, result));
        Path path = tempDir.resolve("quality-duplicate-case.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Files.writeString(path, mapper.writeValueAsString(state));

        assertThatThrownBy(() -> new QualityEvidenceStore(path, mapper))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsDuplicateSuiteAndCaseIdsDuringStartup() throws Exception {
        EvaluationSuite suite = new EvaluationSuite("smoke", "Smoke", "1.0", true,
                java.util.List.of(new EvaluationCase("case-1", "Case")));
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(suite, suite), null,
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        Path path = tempDir.resolve("quality-duplicate-suite.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Files.writeString(path, mapper.writeValueAsString(state));

        assertThatThrownBy(() -> new QualityEvidenceStore(path, mapper))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsMultipleEnabledVersionsForTheSameSuiteDuringStartup() {
        EvaluationSuite v1 = new EvaluationSuite("release", "Release", "release-v1", true,
                java.util.List.of(new EvaluationCase("case-1", "Case")));
        EvaluationSuite v2 = new EvaluationSuite("release", "Release", "release-v2", true,
                java.util.List.of(new EvaluationCase("case-1", "Case")));
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(v1, v2), null,
                java.util.List.of(), java.util.List.of(), java.util.List.of());

        assertThatThrownBy(() -> new QualityEvidenceStore(tempDir.resolve("quality-multiple-enabled.json"),
                new ObjectMapper().findAndRegisterModules()).save(state))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsOrphanCaseResultsDuringStartup() throws Exception {
        EvaluationCaseResult result = new EvaluationCaseResult("missing-run", "case-1", "case-1",
                RunnerExecutionStatus.SUCCEEDED, true, 100, 10, "", "passed", "mock", Instant.now());
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(result));
        Path path = tempDir.resolve("quality-orphan-case.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Files.writeString(path, mapper.writeValueAsString(state));

        assertThatThrownBy(() -> new QualityEvidenceStore(path, mapper))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsOrphanQualitySnapshotsDuringStartup() throws Exception {
        QualitySnapshot snapshot = new QualitySnapshot("missing-run", "skill-a", "1.0.0", "smoke", "1.0",
                "runner", "provider", "mock", Instant.now(), 100, 1, 1, true, "rules", 100, 1.0,
                QualityGateStatus.PASSED, java.util.List.of());
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(), java.util.List.of(snapshot), java.util.List.of());
        Path path = tempDir.resolve("quality-orphan-snapshot.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Files.writeString(path, mapper.writeValueAsString(state));

        assertThatThrownBy(() -> new QualityEvidenceStore(path, mapper))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsQualitySnapshotWhoseSkillVersionDiffersFromItsRun() {
        Instant measuredAt = Instant.parse("2026-08-24T02:00:00Z");
        EvaluationRun run = new EvaluationRun("run-identity", "skill-a", "1.0.0", "smoke", "1.0",
                EvaluationRunStatus.COMPLETED, "runner", "provider", "mock", measuredAt, measuredAt,
                1, 1, 100, "", QualityGateStatus.PASSED, java.util.List.of());
        QualitySnapshot mismatched = new QualitySnapshot("run-identity", "skill-b", "2.0.0", "smoke", "1.0",
                "runner", "provider", "mock", measuredAt, 100, 1, 1, true, "rules", 100, 1.0,
                QualityGateStatus.PASSED, java.util.List.of());
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(run), java.util.List.of(mismatched), java.util.List.of());

        assertThatThrownBy(() -> new QualityEvidenceStore(tempDir.resolve("quality-mismatched-snapshot.json"),
                new ObjectMapper().findAndRegisterModules()).save(state))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsQualitySnapshotForNonCompletedEvaluationRun() {
        Instant measuredAt = Instant.parse("2026-08-24T02:00:00Z");
        EvaluationRun run = new EvaluationRun("run-not-complete", "skill-a", "1.0.0", "smoke", "1.0",
                EvaluationRunStatus.FAILED, "runner", "provider", "mock", measuredAt, measuredAt,
                1, 0, 0, "EVALUATION_FAILED", QualityGateStatus.BLOCKED, java.util.List.of("EVALUATION_FAILED"));
        QualitySnapshot snapshot = new QualitySnapshot("run-not-complete", "skill-a", "1.0.0", "smoke", "1.0",
                "runner", "provider", "mock", measuredAt, 0, 1, 0, true, "rules", 0, 0.0,
                QualityGateStatus.BLOCKED, java.util.List.of("EVALUATION_FAILED"));
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(run), java.util.List.of(snapshot), java.util.List.of());

        assertThatThrownBy(() -> new QualityEvidenceStore(tempDir.resolve("quality-non-completed-snapshot.json"),
                new ObjectMapper().findAndRegisterModules()).save(state))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsQualitySnapshotWhoseSuiteDiffersFromItsRun() {
        Instant measuredAt = Instant.parse("2026-08-24T02:00:00Z");
        EvaluationRun run = new EvaluationRun("run-suite-identity", "skill-a", "1.0.0", "smoke", "1.0",
                EvaluationRunStatus.COMPLETED, "runner", "provider", "mock", measuredAt, measuredAt,
                1, 1, 100, "", QualityGateStatus.PASSED, java.util.List.of());
        QualitySnapshot mismatched = new QualitySnapshot("run-suite-identity", "skill-a", "1.0.0", "regression", "2.0",
                "runner", "provider", "mock", measuredAt, 100, 1, 1, true, "rules", 100, 1.0,
                QualityGateStatus.PASSED, java.util.List.of());
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(run), java.util.List.of(mismatched), java.util.List.of());

        assertThatThrownBy(() -> new QualityEvidenceStore(tempDir.resolve("quality-mismatched-suite.json"),
                new ObjectMapper().findAndRegisterModules()).save(state))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsQualitySnapshotWhoseProvidersOrDataSourceDifferFromItsRun() {
        Instant measuredAt = Instant.parse("2026-08-24T02:00:00Z");
        EvaluationRun run = new EvaluationRun("run-provider-identity", "skill-a", "1.0.0", "smoke", "1.0",
                EvaluationRunStatus.COMPLETED, "mock-runner", "mock-evaluation", "mock", measuredAt, measuredAt,
                1, 1, 100, "", QualityGateStatus.PASSED, java.util.List.of());
        QualitySnapshot mismatched = new QualitySnapshot("run-provider-identity", "skill-a", "1.0.0", "smoke", "1.0",
                "openclaw-runner", "deepeval-evaluation", "production", measuredAt, 100, 1, 1, true,
                "rules", 100, 1.0, QualityGateStatus.PASSED, java.util.List.of());
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(run), java.util.List.of(mismatched), java.util.List.of());

        assertThatThrownBy(() -> new QualityEvidenceStore(tempDir.resolve("quality-mismatched-provider.json"),
                new ObjectMapper().findAndRegisterModules()).save(state))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsQualitySnapshotWhoseExecutionEnvironmentDiffersFromItsRun() {
        Instant measuredAt = Instant.parse("2026-08-24T02:00:00Z");
        EvaluationRun run = new EvaluationRun("run-environment-identity", "skill-a", "1.0.0", "smoke", "1.0",
                EvaluationRunStatus.COMPLETED, "runner", "provider", "mock", measuredAt, measuredAt,
                1, 1, 100, "", QualityGateStatus.PASSED, java.util.List.of(), "runtime-a", "mcp-a", "llm-a");
        QualitySnapshot mismatched = new QualitySnapshot("run-environment-identity", "skill-a", "1.0.0",
                "smoke", "1.0", "runner", "provider", "mock", measuredAt, 100, 1, 1, true,
                "rules", 100, 1.0, QualityGateStatus.PASSED, java.util.List.of(),
                "runtime-b", "mcp-a", "llm-a");
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(run), java.util.List.of(mismatched), java.util.List.of());

        assertThatThrownBy(() -> new QualityEvidenceStore(tempDir.resolve("quality-mismatched-environment.json"),
                new ObjectMapper().findAndRegisterModules()).save(state))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsCompletedEvaluationRunWithoutCompletionTime() {
        Instant createdAt = Instant.parse("2026-08-24T02:00:00Z");
        EvaluationRun invalid = new EvaluationRun("run-missing-completed-at", "skill-a", "1.0.0", "smoke", "1.0",
                EvaluationRunStatus.COMPLETED, "runner", "provider", "mock", createdAt, null,
                1, 1, 100, "", QualityGateStatus.PASSED, java.util.List.of());
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(invalid), java.util.List.of(), java.util.List.of());

        assertThatThrownBy(() -> new QualityEvidenceStore(tempDir.resolve("quality-missing-completed-at.json"),
                new ObjectMapper().findAndRegisterModules()).save(state))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsFailedEvaluationRunWithoutCompletionTime() {
        Instant createdAt = Instant.parse("2026-08-24T02:00:00Z");
        EvaluationRun invalid = new EvaluationRun("run-failed-missing-completed-at", "skill-a", "1.0.0", "smoke", "1.0",
                EvaluationRunStatus.FAILED, "runner", "provider", "mock", createdAt, null,
                1, 0, 0, "PROVIDER_ERROR", QualityGateStatus.BLOCKED, java.util.List.of("PROVIDER_ERROR"));
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(), null,
                java.util.List.of(invalid), java.util.List.of(), java.util.List.of());

        assertThatThrownBy(() -> new QualityEvidenceStore(tempDir.resolve("quality-failed-missing-completed-at.json"),
                new ObjectMapper().findAndRegisterModules()).save(state))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    @Test
    void rejectsEvaluationRunsReferencingUnknownSuitesDuringStartup() throws Exception {
        EvaluationRun run = new EvaluationRun("run-1", "skill-a", "1.0.0", "missing-suite", "1.0",
                EvaluationRunStatus.COMPLETED, "runner", "provider", "mock", Instant.now(), Instant.now(),
                1, 1, 100, "", QualityGateStatus.PASSED, java.util.List.of());
        EvaluationSuite knownSuite = new EvaluationSuite("smoke", "Smoke", "1.0", true,
                java.util.List.of(new EvaluationCase("case-1", "Case")));
        QualityEvidenceState state = new QualityEvidenceState(java.util.List.of(knownSuite), null,
                java.util.List.of(run), java.util.List.of(), java.util.List.of());
        Path path = tempDir.resolve("quality-orphan-suite.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Files.writeString(path, mapper.writeValueAsString(state));

        assertThatThrownBy(() -> new QualityEvidenceStore(path, mapper))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    private static final class ThrowingRunner implements SkillRunner {
        @Override public String providerId() { return "throwing-runner"; }
        @Override public String providerVersion() { return "1.0"; }
        @Override public RunnerExecutionResult execute(RunnerExecutionRequest request) {
            throw new IllegalStateException("provider-secret-not-for-client");
        }
    }

    private EvaluationRun awaitCompleted(QualityEvaluationService service, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        EvaluationRun current;
        do {
            current = service.find(id);
            if (current.status() == EvaluationRunStatus.COMPLETED) return current;
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        } while (System.nanoTime() < deadline);
        return current;
    }
}
