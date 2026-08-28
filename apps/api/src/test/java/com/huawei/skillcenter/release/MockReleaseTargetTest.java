package com.huawei.skillcenter.release;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MockReleaseTargetTest {
    @Test
    void succeedsDeterministicallyForNormalRelease() {
        ReleaseRecord release = ReleaseRecord.request("release-1", "skill-a", "1.0.0", "sha-1",
                ReleaseEnvironment.STAGING, ReleaseGateSnapshot.passed(Instant.now()), "idem-1", "admin", Instant.now());

        ReleaseTargetResult result = new MockReleaseTarget().promote(release);

        assertThat(result.success()).isTrue();
        assertThat(result.reference()).isEqualTo("mock/release-1");
        assertThat(result.reasonCode()).isBlank();
    }

    @Test
    void exposesDeterministicFailureForFailureFixture() {
        ReleaseRecord release = ReleaseRecord.request("fail-release", "skill-a", "1.0.0", "sha-1",
                ReleaseEnvironment.STAGING, ReleaseGateSnapshot.passed(Instant.now()), "idem-1", "admin", Instant.now());

        ReleaseTargetResult result = new MockReleaseTarget().promote(release);

        assertThat(result.success()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("MOCK_TARGET_FAILED");
    }
}
