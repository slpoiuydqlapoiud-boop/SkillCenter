package com.huawei.skillcenter.release;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseRecordTest {
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");

    @Test
    void promotesOnlyAfterApprovalAndKeepsReleaseContextImmutable() {
        ReleaseRecord requested = ReleaseRecord.request(
                "release-1", "skill-a", "1.2.0", "sha-1", ReleaseEnvironment.STAGING,
                ReleaseGateSnapshot.passed(NOW), "idem-1", "admin", NOW);

        ReleaseRecord promoted = requested.approve("admin-2", NOW.plusSeconds(60))
                .promoting("target-1", NOW.plusSeconds(120))
                .promoted("target-1", NOW.plusSeconds(180));

        assertThat(promoted.status()).isEqualTo(ReleaseStatus.PROMOTED);
        assertThat(promoted.sha256()).isEqualTo("sha-1");
        assertThat(promoted.targetEnvironment()).isEqualTo(ReleaseEnvironment.STAGING);
        assertThatThrownBy(() -> promoted.approve("admin-3", NOW.plusSeconds(240)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsProductionReleaseWithoutEvidence() {
        assertThatThrownBy(() -> ReleaseRecord.request(
                "release-1", "skill-a", "1.2.0", "sha-1", ReleaseEnvironment.PRODUCTION,
                new ReleaseGateSnapshot(NOW, "NO_EVIDENCE", List.<String>of(), "", "", "", "", "", "", "", "", "", ""),
                "idem-1", "admin", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_EVIDENCE");
    }

    @Test
    void failedReleaseIsTerminalAndRequiresASeparateAttempt() {
        ReleaseRecord failed = ReleaseRecord.request(
                        "release-1", "skill-a", "1.2.0", "sha-1", ReleaseEnvironment.STAGING,
                        ReleaseGateSnapshot.passed(NOW), "idem-1", "admin", NOW)
                .approve("admin-2", NOW.plusSeconds(60))
                .promoting("target-1", NOW.plusSeconds(120))
                .failed("RELEASE_TARGET_FAILED", "target-1", NOW.plusSeconds(180));

        assertThat(failed.status()).isEqualTo(ReleaseStatus.FAILED);
        assertThatThrownBy(() -> failed.promoted("target-1", NOW.plusSeconds(240)))
                .isInstanceOf(IllegalStateException.class);
    }
}
