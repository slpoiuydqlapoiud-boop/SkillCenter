package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.operations.RuntimeOperationsQuery;
import com.huawei.skillcenter.operations.RuntimeOperationsService;
import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
import com.huawei.skillcenter.operations.RuntimeOperationsWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptimizationSuggestionDispositionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void updatesSuggestionStatusAndWritesAuditWithoutChangingSkillData() throws Exception {
        QualityEvaluationService quality = mock(QualityEvaluationService.class);
        RuntimeOperationsService runtime = mock(RuntimeOperationsService.class);
        GovernanceStore governance = mock(GovernanceStore.class);
        when(quality.snapshots("skill-a")).thenReturn(List.of());
        when(runtime.snapshot(any(RuntimeOperationsQuery.class))).thenReturn(new RuntimeOperationsSnapshot(
                "24h", Instant.now(), "mock", new RuntimeOperationsSnapshot.Filters("skill-a", "1.0.0", null),
                new RuntimeOperationsSnapshot.Totals(0, 0, 0, 0, 0, 0),
                new RuntimeOperationsSnapshot.Latency(0, 0, 0, 0), List.of(), List.of(), List.of(), List.of()));
        OptimizationSuggestionDispositionStore store = new OptimizationSuggestionDispositionStore(
                tempDir.resolve("dispositions.json"), new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        OptimizationSuggestionThresholdsStore thresholds = new OptimizationSuggestionThresholdsStore(
                tempDir.resolve("thresholds.json"), new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        OptimizationSuggestionService service = new OptimizationSuggestionService(
                quality, runtime, new OptimizationSuggestionCalculator(), store, thresholds, governance);

        OptimizationSuggestion updated = service.updateDisposition(
                "skill-a", "1.0.0", "runtime-data", RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                null, new OptimizationSuggestionDispositionRequest("ACKNOWLEDGED", "补充样本"),
                new Actor("admin", "admin"), "request-1");

        assertThat(updated.dispositionStatus()).isEqualTo("ACKNOWLEDGED");
        assertThat(updated.dispositionNote()).isEqualTo("补充样本");
        verify(governance).addAudit(any());
        assertThat(Files.exists(tempDir.resolve("dispositions.json"))).isTrue();
    }

    @Test
    void rejectsDeveloperMutationAndUnknownSuggestion() {
        QualityEvaluationService quality = mock(QualityEvaluationService.class);
        RuntimeOperationsService runtime = mock(RuntimeOperationsService.class);
        GovernanceStore governance = mock(GovernanceStore.class);
        when(quality.snapshots("skill-a")).thenReturn(List.of());
        when(runtime.snapshot(any(RuntimeOperationsQuery.class))).thenReturn(new RuntimeOperationsSnapshot(
                "24h", Instant.now(), "mock", RuntimeOperationsSnapshot.Filters.empty(),
                RuntimeOperationsSnapshot.Totals.empty(), RuntimeOperationsSnapshot.Latency.empty(), List.of(), List.of(), List.of(), List.of()));
        OptimizationSuggestionService service = new OptimizationSuggestionService(
                quality, runtime, new OptimizationSuggestionCalculator(),
                new OptimizationSuggestionDispositionStore(tempDir.resolve("dispositions.json"),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new OptimizationSuggestionThresholdsStore(tempDir.resolve("thresholds.json"),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()), governance);

        assertThatThrownBy(() -> service.updateDisposition("skill-a", "1.0.0", "runtime-data",
                RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null, new OptimizationSuggestionDispositionRequest("ACKNOWLEDGED", "x"),
                new Actor("developer", "developer"), "request-2"))
                .isInstanceOf(com.huawei.skillcenter.governance.ForbiddenException.class);
        assertThatThrownBy(() -> service.updateDisposition("skill-a", "1.0.0", "not-found",
                RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null, new OptimizationSuggestionDispositionRequest("DISMISSED", "x"),
                new Actor("admin", "admin"), "request-3"))
                .isInstanceOf(OptimizationSuggestionNotFoundException.class);
    }

    @Test
    void acceptsQualitySnapshotEvidenceForTheSameSkillVersionAndReturnsIt() {
        QualityEvaluationService quality = mock(QualityEvaluationService.class);
        RuntimeOperationsService runtime = mock(RuntimeOperationsService.class);
        GovernanceStore governance = mock(GovernanceStore.class);
        when(quality.snapshots("skill-a")).thenReturn(List.of());
        when(quality.findSnapshot("snapshot-1")).thenReturn(new QualitySnapshot(
                "snapshot-1", "skill-a", "1.0.0", "smoke", "smoke-v1", "mock-runner", "mock-eval",
                "mock", Instant.now(), 94, 2, 2, true, "quality-v1", 96, 1.0,
                QualityGateStatus.PASSED, List.of()));
        when(runtime.snapshot(any(RuntimeOperationsQuery.class))).thenReturn(new RuntimeOperationsSnapshot(
                "24h", Instant.now(), "mock", new RuntimeOperationsSnapshot.Filters("skill-a", "1.0.0", null),
                RuntimeOperationsSnapshot.Totals.empty(), RuntimeOperationsSnapshot.Latency.empty(), List.of(), List.of(), List.of(), List.of()));
        OptimizationSuggestionService service = new OptimizationSuggestionService(
                quality, runtime, new OptimizationSuggestionCalculator(),
                new OptimizationSuggestionDispositionStore(tempDir.resolve("dispositions.json"),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new OptimizationSuggestionThresholdsStore(tempDir.resolve("thresholds.json"),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()), governance);

        OptimizationSuggestion updated = service.updateDisposition(
                "skill-a", "1.0.0", "quality-data", RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null,
                new OptimizationSuggestionDispositionRequest("ACKNOWLEDGED", "已复核", "QUALITY_SNAPSHOT", "snapshot-1"),
                new Actor("admin", "admin"), "request-evidence");

        assertThat(updated.dispositionEvidenceType()).isEqualTo("QUALITY_SNAPSHOT");
        assertThat(updated.dispositionEvidenceId()).isEqualTo("snapshot-1");
    }

    @Test
    void rejectsEvidenceThatBelongsToAnotherSkillVersion() {
        QualityEvaluationService quality = mock(QualityEvaluationService.class);
        RuntimeOperationsService runtime = mock(RuntimeOperationsService.class);
        when(quality.snapshots("skill-a")).thenReturn(List.of());
        when(quality.findSnapshot("snapshot-other")).thenReturn(new QualitySnapshot(
                "snapshot-other", "skill-b", "1.0.0", "smoke", "smoke-v1", "mock-runner", "mock-eval",
                "mock", Instant.now(), 94, 2, 2, true, "quality-v1", 96, 1.0,
                QualityGateStatus.PASSED, List.of()));
        when(runtime.snapshot(any(RuntimeOperationsQuery.class))).thenReturn(new RuntimeOperationsSnapshot(
                "24h", Instant.now(), "mock", RuntimeOperationsSnapshot.Filters.empty(),
                RuntimeOperationsSnapshot.Totals.empty(), RuntimeOperationsSnapshot.Latency.empty(), List.of(), List.of(), List.of(), List.of()));
        OptimizationSuggestionService service = new OptimizationSuggestionService(
                quality, runtime, new OptimizationSuggestionCalculator(),
                new OptimizationSuggestionDispositionStore(tempDir.resolve("dispositions.json"),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new OptimizationSuggestionThresholdsStore(tempDir.resolve("thresholds.json"),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()), mock(GovernanceStore.class));

        assertThatThrownBy(() -> service.updateDisposition(
                "skill-a", "1.0.0", "quality-data", RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null,
                new OptimizationSuggestionDispositionRequest("RESOLVED", "x", "QUALITY_SNAPSHOT", "snapshot-other"),
                new Actor("admin", "admin"), "request-mismatch"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match skill/version");
    }

    @Test
    void marksPersistedDispositionEvidenceAsExpiredAfterEvidenceIsRemoved() {
        QualityEvaluationService quality = mock(QualityEvaluationService.class);
        RuntimeOperationsService runtime = mock(RuntimeOperationsService.class);
        when(quality.snapshots("skill-a")).thenReturn(List.of());
        when(quality.findSnapshot("snapshot-expired")).thenReturn(null);
        when(runtime.snapshot(any(RuntimeOperationsQuery.class))).thenReturn(new RuntimeOperationsSnapshot(
                "24h", Instant.now(), "mock", new RuntimeOperationsSnapshot.Filters("skill-a", "1.0.0", null),
                RuntimeOperationsSnapshot.Totals.empty(), RuntimeOperationsSnapshot.Latency.empty(), List.of(), List.of(), List.of(), List.of()));
        OptimizationSuggestionDispositionStore store = new OptimizationSuggestionDispositionStore(
                tempDir.resolve("expired-disposition.json"), new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        store.upsert(new OptimizationSuggestionDisposition("skill-a", "1.0.0", "quality-data", "RESOLVED",
                "旧证据已清理", "admin", "admin", Instant.now(),
                OptimizationSuggestionDisposition.QUALITY_SNAPSHOT, "snapshot-expired"));
        OptimizationSuggestionService service = new OptimizationSuggestionService(
                quality, runtime, new OptimizationSuggestionCalculator(), store,
                new OptimizationSuggestionThresholdsStore(tempDir.resolve("expired-thresholds.json"),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()), mock(GovernanceStore.class));

        OptimizationSuggestion suggestion = service.suggestions("skill-a", "1.0.0",
                RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null).stream()
                .filter(item -> item.id().equals("quality-data")).findFirst().orElseThrow();

        assertThat(suggestion.dispositionEvidenceStatus()).isEqualTo("EXPIRED");
        assertThat(suggestion.dispositionEvidenceId()).isEqualTo("snapshot-expired");
    }
}
