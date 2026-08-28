package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcQualityEvidenceStoreTest {
    @Test
    void validatorRejectsSnapshotReferencingUnknownRun() {
        QualityEvidenceState invalid = new QualityEvidenceState(List.of(), null, List.of(),
                List.of(new QualitySnapshot("missing-run", "skill-a", "1.0.0", "smoke", "1.0",
                        "runner", "provider", "test", Instant.parse("2026-08-25T00:00:00Z"),
                        100, 1, 1, true, "quality-v1", 100, 1.0,
                        QualityGateStatus.PASSED, List.of())), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> QualityEvidenceStateValidator.validate(invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
