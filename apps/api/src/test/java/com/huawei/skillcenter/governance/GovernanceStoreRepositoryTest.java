package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.search.SkillSearchRefreshEvent;
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

    @Test
    void auditAppendReloadsLatestStateWhenAnotherInstanceWinsTheRevision() {
        RecordingRepository repository = new RecordingRepository();
        GovernanceStore store = new GovernanceStore(repository, List.of());
        AuditEvent externalAudit = new AuditEvent("audit-external", "EXTERNAL_UPDATE", "SKILL", "skill-1",
                "other-instance", "admin", "request-external", Instant.parse("2026-08-25T00:00:00Z"), Map.of());
        repository.conflictOnceWith(externalAudit);

        store.addAudit(new AuditEvent("audit-local", "LOCAL_UPDATE", "SKILL", "skill-1", "admin", "admin",
                "request-local", Instant.parse("2026-08-25T00:00:01Z"), Map.of()));

        assertThat(repository.state.revision()).isEqualTo(2L);
        assertThat(repository.state.snapshot().audits()).extracting(AuditEvent::auditId)
                .containsExactly("audit-external", "audit-local");
    }

    @Test
    void eventAwareReviewWriteForwardsRefreshIntentToTheRepository() {
        RecordingRepository repository = new RecordingRepository();
        GovernanceStore store = new GovernanceStore(repository, List.of());
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        SkillVersion version = new SkillVersion("package-1", "skill-1", "1.0.0", "published",
                "0".repeat(64), 1, "", "owner", now, "admin", now, "review-1");
        ReviewTask review = new ReviewTask("review-1", "package-1", "skill-1", "1.0.0", "approved",
                "owner", now, "admin", now, null);
        SkillSearchRefreshEvent event = new SkillSearchRefreshEvent("skill-1", 7L, "VERSION_PUBLISHED");

        store.updateReview("review-1", review, version,
                new AuditEvent("audit-2", "PACKAGE_APPROVED", "SKILL_VERSION", "package-1", "admin", "admin",
                        "request-2", now, Map.of()), event);

        assertThat(repository.refreshEvents).extracting(SkillSearchRefreshEvent::eventKey)
                .containsExactly(event.eventKey());
    }

    private static final class RecordingRepository implements GovernanceStateRepository {
        private GovernanceStateRepository.GovernanceState state;
        private List<com.huawei.skillcenter.search.SkillSearchRefreshEvent> refreshEvents = List.of();
        private AuditEvent conflictAudit;

        private void conflictOnceWith(AuditEvent audit) {
            conflictAudit = audit;
        }

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
            if (conflictAudit != null) {
                List<AuditEvent> audits = new java.util.ArrayList<>(state.snapshot().audits());
                audits.add(conflictAudit);
                state = new GovernanceStateRepository.GovernanceState(state.revision() + 1,
                        new GovernanceSnapshot(state.snapshot().versions(), state.snapshot().reviews(),
                                state.snapshot().installations(), audits));
                conflictAudit = null;
                throw new GovernanceStateConflictException("stale");
            }
            if (state.revision() != expectedRevision) {
                throw new GovernanceStateConflictException("stale");
            }
            state = new GovernanceStateRepository.GovernanceState(expectedRevision + 1, snapshot);
            return state;
        }

        @Override
        public GovernanceStateRepository.GovernanceState replace(long expectedRevision, GovernanceSnapshot snapshot,
                                                                  List<com.huawei.skillcenter.search.SkillSearchRefreshEvent> events) {
            refreshEvents = List.copyOf(events);
            return replace(expectedRevision, snapshot);
        }
    }
}
