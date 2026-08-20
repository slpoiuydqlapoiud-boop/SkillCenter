package com.huawei.skillcenter.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.events.InvocationEvent;
import com.huawei.skillcenter.events.InvocationEventService;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationRecord;
import com.huawei.skillcenter.governance.ReviewTask;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.skill.PageResult;
import com.huawei.skillcenter.skill.SkillCatalogService;
import com.huawei.skillcenter.skill.SkillQuery;
import com.huawei.skillcenter.skill.SkillRecord;
import com.huawei.skillcenter.skill.SkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalCenterServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void favoritesAndInstallationsAreScopedToActor() {
        GovernanceStore store = storeWithVersion("demo", "1.0.0", "published", "alice");
        store.addInstallation(installation("i-alice", "alice"), audit("i-audit"));
        store.addInstallation(installation("i-bob", "bob"), audit("i-audit-b"));
        PersonalCenterService service = service(store, new InvocationEventService());

        Actor alice = new Actor("alice", "viewer");
        service.addFavorite(alice, "demo", "req-1");

        assertThat(service.installations(alice, null, null, null)).extracting(InstallationRecord::installationId)
                .containsExactly("i-alice");
        assertThat(service.favorites(alice)).extracting(PersonalSkillView::id).containsExactly("demo");
        assertThat(service.favorites(new Actor("bob", "viewer"))).isEmpty();
    }

    @Test
    void invocationHistoryOnlyReturnsSafeFieldsAndPaginates() {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("events.json"), List.of());
        InvocationEventService events = new InvocationEventService();
        events.ingest(event("alice", "demo", "1.0.0", "success", OffsetDateTime.parse("2026-08-17T00:00:00Z")));
        events.ingest(event("bob", "demo", "1.0.0", "success", OffsetDateTime.parse("2026-08-17T00:01:00Z")));
        PersonalCenterService service = service(store, events);

        InvocationHistoryPage page = service.invocations(new Actor("alice", "viewer"), null, null,
                null, null, 1, 1);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.skillId()).isEqualTo("demo");
            assertThat(item.clientType()).isEqualTo("codex");
            assertThat(item.errorCode()).isNull();
        });
    }

    @Test
    void mySkillsUsesUploadedByAndDeduplicates() {
        GovernanceStore store = storeWithVersion("demo", "1.0.0", "published", "alice");
        store.createPendingVersion(new SkillVersion("p2", "demo", "1.1.0", "deprecated", "b".repeat(64), 1, "",
                "alice", Instant.parse("2026-08-17T00:02:00Z"), "reviewer", Instant.parse("2026-08-17T00:03:00Z"), "r2"),
                new ReviewTask("r2", "p2", "demo", "1.1.0", "approved", "alice", Instant.now(), "reviewer", Instant.now(), null),
                audit("version-2"));
        PersonalCenterService service = service(store, new InvocationEventService());

        assertThat(service.mySkills(new Actor("alice", "maintainer"))).extracting(PersonalSkillView::id)
                .containsExactly("demo");
    }

    private PersonalCenterService service(GovernanceStore store, InvocationEventService events) {
        SkillRepository repository = new SkillRepository() {
            @Override
            public PageResult<SkillRecord> findPublished(SkillQuery query) {
                return new PageResult<>(List.of(), query.page(), query.pageSize(), 0);
            }

            @Override
            public Optional<SkillRecord> findDetail(String skillId) {
                return Optional.empty();
            }
        };
        return new PersonalCenterService(store, new SkillCatalogService(repository, store,
                new ObjectMapper().findAndRegisterModules()), events);
    }

    private GovernanceStore storeWithVersion(String skillId, String version, String status, String uploadedBy) {
        GovernanceStore store = new GovernanceStore(tempDir.resolve("state-" + System.nanoTime() + ".json"), List.of());
        store.createPendingVersion(new SkillVersion("p1", skillId, version, status, "a".repeat(64), 1, "", uploadedBy,
                Instant.parse("2026-08-17T00:00:00Z"), "reviewer", Instant.parse("2026-08-17T00:01:00Z"), "r1"),
                new ReviewTask("r1", "p1", skillId, version, "approved", uploadedBy, Instant.now(), "reviewer", Instant.now(), null),
                audit("version-1"));
        return store;
    }

    private InstallationRecord installation(String id, String user) {
        return new InstallationRecord(id, "m-" + id, "demo", "1.0.0", "codex", "1", user, "installed",
                Instant.now(), Instant.now(), "team-a", null, "cli", null, null, null, null);
    }

    private InvocationEvent event(String user, String skillId, String version, String status, OffsetDateTime occurredAt) {
        return new InvocationEvent("1.0", UUID.randomUUID(), occurredAt, skillId, version,
                new InvocationEvent.Subject(user, "team-a"), new InvocationEvent.Client("codex", "1"),
                "session-1234567890", status, 20, null,
                new InvocationEvent.Usage("model", 1, 1));
    }

    private AuditEvent audit(String id) {
        return new AuditEvent(id, "TEST", "TEST", id, "tester", "admin", "req", Instant.now(), Map.of());
    }
}
