package com.huawei.skillcenter.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillScope;
import com.huawei.skillcenter.access.SkillScopeMutation;
import com.huawei.skillcenter.access.SkillScopeRepository;
import com.huawei.skillcenter.access.SkillVisibility;
import com.huawei.skillcenter.events.InvocationEventService;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.QualityGateBlockedException;
import com.huawei.skillcenter.governance.QualityReleaseGate;
import com.huawei.skillcenter.governance.ReviewService;
import com.huawei.skillcenter.governance.ReviewTask;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.governance.VersionLifecycleRequest;
import com.huawei.skillcenter.governance.VersionLifecycleService;
import com.huawei.skillcenter.packageupload.PackageValidationResult;
import com.huawei.skillcenter.packageupload.StoredPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillSearchRefreshEventWiringTest {
    @TempDir
    Path tempDir;

    @Test
    void successfulLifecycleAndScopeWritesPublishOnlyBoundedRefreshEvents() {
        RecordingPublisher publisher = new RecordingPublisher();
        GovernanceStore store = new GovernanceStore(tempDir.resolve("successful.json"), List.of());
        ReviewService reviews = new ReviewService(store, (skillId, version) -> { }, null, null, publisher);
        ReviewTask review = reviews.submitValidatedPackage(
                new PackageValidationResult(true, "event-skill", "1.0.0", "a".repeat(64), 1, List.of()),
                new StoredPackage("package-event", "C:/safe/package.zip"), new Actor("author", "maintainer"), "req-submit");

        reviews.approve(review.reviewId(), new Actor("reviewer", "reviewer"), "req-publish");
        new VersionLifecycleService(store, new InvocationEventService(), null, null, publisher).deprecate(
                "event-skill", "1.0.0", new VersionLifecycleRequest("superseded", null),
                new Actor("admin", "admin"), "req-deprecate");
        SkillAuthorizationService authorization = new SkillAuthorizationService(new InMemoryScopes(), store,
                java.time.Clock.systemUTC(), null, publisher);
        authorization.updateScope("event-skill", new SkillScopeMutation(SkillVisibility.RESTRICTED, "",
                List.of("author"), 0, "admin", "admin"), new Actor("admin", "admin"), "req-scope");
        authorization.updateScope("event-skill", new SkillScopeMutation(SkillVisibility.PUBLIC, "",
                List.of(), 1, "admin", "admin"), new Actor("admin", "admin"), "req-scope-update");

        List<SkillSearchRefreshEvent> events = publisher.refreshEvents();
        assertThat(events).extracting(SkillSearchRefreshEvent::reasonCode)
                .containsExactly("VERSION_PUBLISHED", "VERSION_DEPRECATED", "SKILL_SCOPE_SAVED", "SKILL_SCOPE_SAVED");
        assertThat(events).extracting(SkillSearchRefreshEvent::sourceRevision)
                .containsExactly(
                        SkillSearchRefreshEvent.stableSourceRevision("package-event", "1.0.0", "VERSION_PUBLISHED"),
                        SkillSearchRefreshEvent.stableSourceRevision("package-event", "1.0.0", "VERSION_DEPRECATED"),
                        1L, 2L);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.skillId()).isEqualTo("event-skill");
            assertThat(event.sourceRevision()).isGreaterThanOrEqualTo(0L);
        });
        assertThat(SkillSearchRefreshEvent.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("skillId", "sourceRevision", "reasonCode")
                .doesNotContain("markdown", "content", "credentials");
    }

    @Test
    void failedDomainWritesDoNotPublishRefreshEvents() {
        RecordingPublisher publisher = new RecordingPublisher();
        GovernanceStore store = new GovernanceStore(tempDir.resolve("failed.json"), List.of());
        QualityReleaseGate blocked = (skillId, version) -> {
            throw new QualityGateBlockedException(skillId, version, List.of("QUALITY_BLOCKED"));
        };
        ReviewService reviews = new ReviewService(store, blocked, null, null, publisher);
        ReviewTask review = reviews.submitValidatedPackage(
                new PackageValidationResult(true, "blocked-skill", "1.0.0", "b".repeat(64), 1, List.of()),
                new StoredPackage("package-blocked", "C:/safe/blocked.zip"), new Actor("author", "maintainer"), "req-submit");

        assertThatThrownBy(() -> reviews.approve(review.reviewId(), new Actor("reviewer", "reviewer"), "req-publish"))
                .isInstanceOf(QualityGateBlockedException.class);
        assertThatThrownBy(() -> new VersionLifecycleService(store, new InvocationEventService(), null, null, publisher)
                .withdraw("blocked-skill", "1.0.0", new VersionLifecycleRequest("not published", null),
                        new Actor("admin", "admin"), "req-withdraw"))
                .isInstanceOf(com.huawei.skillcenter.governance.VersionStateConflictException.class);
        assertThatThrownBy(() -> new SkillAuthorizationService(new InMemoryScopes(), store,
                java.time.Clock.systemUTC(), null, publisher).updateScope("blocked-skill",
                new SkillScopeMutation(SkillVisibility.PUBLIC, "", List.of(), 1, "admin", "admin"),
                new Actor("admin", "admin"), "req-scope"))
                .isInstanceOf(com.huawei.skillcenter.access.SkillScopeConflictException.class);

        assertThat(publisher.refreshEvents()).isEmpty();
    }

    @Test
    void springDiscoversCoordinatorEventListenerAndPreservesCommittedHits() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        SkillSearchDocument document = new SkillSearchDocument("event-skill", "Event skill", "summary",
                List.of("event"), "platform", "other", "published", "low", Instant.now(), Instant.now(),
                "1.0.0", "PUBLIC", "");
        index.rebuild(List.of(document), "event-hash");
        SkillSearchRefreshCoordinator coordinator = new SkillSearchRefreshCoordinator(index,
                new GovernedSkillSearchDocumentSource(new GovernanceStore(tempDir.resolve("listener.json"), List.of()),
                        new EmptyRepository()));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SkillSearchRefreshCoordinator.class, () -> coordinator);
            context.refresh();
            context.publishEvent(new SkillSearchRefreshEvent("event-skill", 9L, "VERSION_WITHDRAWN"));

            assertThat(index.status().state()).isEqualTo("STALE");
            assertThat(index.search(new SkillSearchQuery("event", "", "", "", "relevance")))
                    .extracting(SkillSearchHit::skillId).containsExactly("event-skill");
        }
    }

    private static final class RecordingPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        private List<SkillSearchRefreshEvent> refreshEvents() {
            return events.stream().filter(SkillSearchRefreshEvent.class::isInstance)
                    .map(SkillSearchRefreshEvent.class::cast).toList();
        }
    }

    private static final class InMemoryScopes implements SkillScopeRepository {
        private final Map<String, SkillScope> values = new LinkedHashMap<>();

        @Override
        public Optional<SkillScope> find(String skillId) {
            return Optional.ofNullable(values.get(skillId));
        }

        @Override
        public List<SkillScope> findAll() {
            return List.copyOf(values.values());
        }

        @Override
        public SkillScope create(SkillScope scope) {
            values.put(scope.skillId(), scope);
            return scope;
        }

        @Override
        public SkillScope replace(SkillScope scope, int expectedRevision) {
            SkillScope existing = values.get(scope.skillId());
            if (existing == null || existing.revision() != expectedRevision) {
                throw new com.huawei.skillcenter.access.SkillScopeConflictException("revision conflict");
            }
            SkillScope updated = new SkillScope(existing.skillId(), scope.visibility(), scope.ownerTeamId(),
                    scope.maintainerUserIds(), existing.revision() + 1, existing.declaredBy(), existing.declaredAt(),
                    scope.updatedBy(), scope.updatedAt());
            values.put(updated.skillId(), updated);
            return updated;
        }
    }

    private static final class EmptyRepository implements com.huawei.skillcenter.skill.SkillRepository {
        @Override
        public com.huawei.skillcenter.skill.PageResult<com.huawei.skillcenter.skill.SkillRecord> findPublished(
                com.huawei.skillcenter.skill.SkillQuery query) {
            return new com.huawei.skillcenter.skill.PageResult<>(List.of(), query.page(), query.pageSize(), 0);
        }

        @Override
        public Optional<com.huawei.skillcenter.skill.SkillRecord> findDetail(String skillId) {
            return Optional.empty();
        }
    }
}
