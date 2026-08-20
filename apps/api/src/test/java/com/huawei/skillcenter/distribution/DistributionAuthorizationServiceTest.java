package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistributionAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T08:00:00Z");

    @TempDir
    Path tempDir;

    private GovernanceStore store;
    private TestClock clock;
    private DistributionAuthorizationService service;

    @BeforeEach
    void setUp() {
        store = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        clock = new TestClock(NOW);
        service = new DistributionAuthorizationService(store, clock);
    }

    @Test
    void issuedTokenCanBeConsumedOnlyOnceBeforeExpiry() {
        DistributionAuthorizationService.IssuedAuthorization issued = service.issue(
                manifest(), installation(), new InstallationRequest("codex", "1.0.0", "cli"),
                new Actor("alice", "viewer"));

        DistributionAuthorization consumed = service.consume(issued.token());

        assertThat(consumed.tokenId()).isEqualTo(issued.tokenId());
        assertThat(consumed.consumedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.consume(issued.token()))
                .isInstanceOf(DistributionAuthorizationException.class)
                .hasMessage("authorization has already been consumed");
    }

    @Test
    void expiredTokenIsRejected() {
        DistributionAuthorizationService.IssuedAuthorization issued = service.issue(
                manifest(), installation(), new InstallationRequest("codex", "1.0.0", "manual-zip"),
                new Actor("alice", "viewer"));

        clock.advance(Duration.ofMinutes(16));

        assertThatThrownBy(() -> service.consume(issued.token()))
                .isInstanceOf(DistributionAuthorizationException.class)
                .hasMessage("authorization has expired");
    }

    @Test
    void mismatchedTokenIdDoesNotConsumeTheAuthorization() {
        DistributionAuthorizationService.IssuedAuthorization issued = service.issue(
                manifest(), installation(), new InstallationRequest("codex", "1.0.0", "cli"),
                new Actor("alice", "viewer"));

        assertThatThrownBy(() -> service.consume("different-token-id", issued.token()))
                .isInstanceOf(DistributionAuthorizationException.class)
                .hasMessage("authorization does not match token id");
        assertThat(service.consume(issued.token()).tokenId()).isEqualTo(issued.tokenId());
    }

    private InstallManifest manifest() {
        return new InstallManifest("1.0", UUID.randomUUID(),
                new InstallManifest.SkillArtifact("eox-query", "EOX 查询", "1.2.0", "published"),
                new InstallManifest.Artifact("https://skill-center.internal/artifacts/eox-query/1.2.0.zip",
                        "a".repeat(64), 2048, "application/zip"),
                List.of(new InstallManifest.Compatibility("codex", "1.0.0", null)),
                new InstallManifest.Permissions("read-only", "read", "internal", "restricted", "none"),
                List.of(), NOW, NOW.plus(Duration.ofDays(1)));
    }

    private InstallationRecord installation() {
        return new InstallationRecord("installation-1", "manifest-1", "eox-query", "1.2.0",
                "codex", "1.0.0", "alice", "requested", NOW, NOW);
    }

    private static final class TestClock extends java.time.Clock {
        private Instant instant;

        private TestClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
