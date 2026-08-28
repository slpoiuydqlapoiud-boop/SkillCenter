package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceStoreRepositoryTest {
    @Test
    void governanceStoreWritesThroughTheSelectedRepositoryAndReloadsItsRevision() {
        RecordingRepository repository = new RecordingRepository();
        GovernanceStore store = new GovernanceStore(repository, List.of());

        store.addAudit(new AuditEvent("audit-1", "TEST", "SKILL", "skill-1", "admin", "admin",
                "request-1", Instant.parse("2026-08-25T00:00:00Z"), Map.of()));

        assertThat(repository.state.revision()).isEqualTo(1L);
        GovernanceStore restarted = new GovernanceStore(repository, List.of());
        assertThat(restarted.snapshot().audits()).hasSize(1);
        assertThat(restarted.snapshot().audits().get(0).auditId()).isEqualTo("audit-1");
    }

    private static final class RecordingRepository implements GovernanceStateRepository {
        private GovernanceStateRepository.GovernanceState state;

        @Override
        public Optional<GovernanceStateRepository.GovernanceState> load() {
            return Optional.ofNullable(state);
        }

        @Override
        public GovernanceStateRepository.GovernanceState loadOrSeed(java.util.function.Supplier<GovernanceSnapshot> seed) {
            if (state == null) state = new GovernanceStateRepository.GovernanceState(0, seed.get());
            return state;
        }

        @Override
        public GovernanceStateRepository.GovernanceState replace(long expectedRevision, GovernanceSnapshot snapshot) {
            if (state.revision() != expectedRevision) {
                throw new GovernanceStateConflictException("stale");
            }
            state = new GovernanceStateRepository.GovernanceState(expectedRevision + 1, snapshot);
            return state;
        }
    }
}
