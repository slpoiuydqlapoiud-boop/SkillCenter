package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompatibilityMatrixEvidencePersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void matrixAndCasesRoundTripThroughTheQualityEvidenceStore() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        QualityEvidenceStore store = new QualityEvidenceStore(tempDir.resolve("matrix.json"), mapper);
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        String matrixId = UUID.randomUUID().toString();
        CompatibilityMatrixRun run = completedMatrix(matrixId, now);
        CompatibilityMatrixCase matrixCase = completedCase(matrixId, now);
        EvaluationRun evaluation = completedEvaluation(now);

        store.save(new QualityEvidenceState(List.of(), null, List.of(evaluation), List.of(), List.of(),
                List.of(run), List.of(matrixCase)));

        QualityEvidenceState restored = new QualityEvidenceStore(tempDir.resolve("matrix.json"), mapper).load();
        assertThat(restored.matrixRuns()).containsExactly(run);
        assertThat(restored.matrixCases()).containsExactly(matrixCase);
    }

    @Test
    void oldQualityEvidenceJsonRecoversWithEmptyMatrixCollections() throws Exception {
        Path path = tempDir.resolve("old.json");
        Files.writeString(path, "{\"suites\":[],\"rules\":null,\"runs\":[],\"snapshots\":[],\"caseResults\":[]}");

        QualityEvidenceState restored = new QualityEvidenceStore(path,
                new ObjectMapper().findAndRegisterModules()).load();

        assertThat(restored.matrixRuns()).isEmpty();
        assertThat(restored.matrixCases()).isEmpty();
    }

    @Test
    void matrixUpdatePreservesExistingQualityEvidence() {
        QualityEvidenceStore store = new QualityEvidenceStore(tempDir.resolve("atomic.json"),
                new ObjectMapper().findAndRegisterModules());
        QualityRuleSet rules = new QualityRuleSet("default", "quality-v1", 80, 0.8, 90);
        store.save(new QualityEvidenceState(List.of(), rules, List.of(), List.of(), List.of()));
        CompatibilityMatrixRun run = completedMatrix(UUID.randomUUID().toString(), Instant.EPOCH);
        CompatibilityMatrixCase matrixCase = completedCase(run.matrixRunId(), Instant.EPOCH);
        EvaluationRun evaluation = completedEvaluation(Instant.EPOCH);

        store.update(state -> new QualityEvidenceState(state.suites(), state.rules(), List.of(evaluation),
                state.snapshots(), state.caseResults(), List.of(run), List.of(matrixCase)));

        assertThat(store.load().rules()).isEqualTo(rules);
        assertThat(store.load().matrixRuns()).containsExactly(run);
    }

    @Test
    void rejectsDuplicateMatrixIdsAndOrphanCases() {
        Instant now = Instant.EPOCH;
        String matrixId = UUID.randomUUID().toString();
        CompatibilityMatrixRun run = completedMatrix(matrixId, now);
        CompatibilityMatrixCase orphan = completedCase(UUID.randomUUID().toString(), now);

        assertThatThrownBy(() -> new QualityEvidenceStore(tempDir.resolve("invalid.json"),
                new ObjectMapper().findAndRegisterModules()).save(new QualityEvidenceState(
                List.of(), null, List.of(), List.of(), List.of(), List.of(run, run), List.of())))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
        assertThatThrownBy(() -> new QualityEvidenceStore(tempDir.resolve("orphan.json"),
                new ObjectMapper().findAndRegisterModules()).save(new QualityEvidenceState(
                List.of(), null, List.of(), List.of(), List.of(), List.of(run), List.of(orphan))))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    }

    private CompatibilityMatrixRun completedMatrix(String matrixId, Instant now) {
        return new CompatibilityMatrixRun(matrixId, "eox-query", "1.3.0", "smoke", "smoke-v1",
                CompatibilityMatrixPolicy.ALL_MUST_PASS, 1, false, CompatibilityMatrixStatus.COMPLETED,
                "mock", "success", 1_000, 1, 1, 1, 100, 1, QualityGateStatus.PASSED, List.of(), "admin", now, now);
    }

    private CompatibilityMatrixCase completedCase(String matrixId, Instant now) {
        return new CompatibilityMatrixCase("case-1", matrixId, "evaluation-1", "openclaw", "", "",
                "context-v1", "", "", com.huawei.skillcenter.execution.ExecutionEnvironmentStatus.ACTIVE,
                null, null, CompatibilityMatrixCaseStatus.COMPLETED, 100, QualityGateStatus.PASSED,
                List.of(), "", now, now);
    }

    private EvaluationRun completedEvaluation(Instant now) {
        return new EvaluationRun("evaluation-1", "eox-query", "1.3.0", "smoke", "smoke-v1",
                EvaluationRunStatus.COMPLETED, "runner", "provider", "mock", now, now,
                1, 1, 100, "", QualityGateStatus.PASSED, List.of(), "openclaw", "", "");
    }
}
