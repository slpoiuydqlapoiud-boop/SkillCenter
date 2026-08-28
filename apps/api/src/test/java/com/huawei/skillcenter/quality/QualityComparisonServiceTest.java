package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.RuntimeOperationsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QualityComparisonServiceTest {
    @Test
    void doesNotUseMockSnapshotForProductionDetail() {
        QualityEvaluationService evaluations = mock(QualityEvaluationService.class);
        RuntimeOperationsService runtime = mock(RuntimeOperationsService.class);
        when(evaluations.snapshots("skill-a", "production", null, null, null)).thenReturn(List.of());

        SkillQualityDetail detail = new QualityComparisonService(evaluations, runtime)
                .detail("skill-a", null, com.huawei.skillcenter.operations.RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                        "production");

        assertThat(detail.latestSnapshot()).isNull();
        verify(evaluations).snapshots("skill-a", "production", null, null, null);
        verifyNoMoreInteractions(evaluations);
    }

    @Test
    void returnsSnapshotHistoryInMeasuredAtDescendingOrder() {
        QualityEvaluationService evaluations = mock(QualityEvaluationService.class);
        RuntimeOperationsService runtime = mock(RuntimeOperationsService.class);
        QualitySnapshot older = snapshot("older", Instant.parse("2026-08-20T00:00:00Z"));
        QualitySnapshot newer = snapshot("newer", Instant.parse("2026-08-21T00:00:00Z"));
        when(evaluations.snapshots("skill-a", null, null, null, null)).thenReturn(List.of(newer, older));

        SkillQualityDetail detail = new QualityComparisonService(evaluations, runtime)
                .detail("skill-a", null, com.huawei.skillcenter.operations.RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                        null);

        assertThat(detail.snapshotHistory()).extracting(QualitySnapshot::snapshotId)
                .containsExactly("newer", "older");
    }

    private QualitySnapshot snapshot(String id, Instant measuredAt) {
        return new QualitySnapshot(id, "skill-a", "1.0.0", "smoke", "smoke-v1", "mock-runner",
                "mock-evaluation", "mock", measuredAt, 90, 1, 1, true, "quality-v1", 90, 1.0,
                QualityGateStatus.PASSED, List.of());
    }
}
