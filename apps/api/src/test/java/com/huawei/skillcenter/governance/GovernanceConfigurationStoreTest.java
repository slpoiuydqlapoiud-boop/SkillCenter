package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceConfigurationStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void oldSnapshotLoadsDefaultGovernanceConfiguration() throws Exception {
        Path state = tempDir.resolve("legacy.json");
        Files.writeString(state, "{\"versions\":[],\"reviews\":[],\"installations\":[],\"audits\":[],\"authorizations\":[],\"favorites\":[]}");

        GovernanceStore store = new GovernanceStore(state, List.of());

        assertThat(store.snapshot().configuration().teams()).isEmpty();
        assertThat(store.snapshot().configuration().platformPolicy().pageSizeOptions()).containsExactly(12, 24, 48);
        assertThat(store.snapshot().configuration().platformPolicy().maxPageSize()).isEqualTo(48);
        assertThat(store.snapshot().configuration().platformPolicy().minimumClientVersion()).isEqualTo("1.0.0");
        assertThat(store.snapshot().configuration().platformPolicy().defaultCollectionVisibility()).isEqualTo("public");
    }

    @Test
    void governanceConfigurationPersistsAndPreservesExistingCollections() {
        Path state = tempDir.resolve("configuration.json");
        GovernanceStore store = new GovernanceStore(state, List.of());
        SkillVersion version = new SkillVersion("p1", "demo", "1.0.0", "published", "a".repeat(64), 1, "",
                "alice", Instant.parse("2026-08-18T00:00:00Z"), "reviewer", Instant.parse("2026-08-18T00:01:00Z"), "r1");
        store.createPendingVersion(version, new ReviewTask("r1", "p1", "demo", "1.0.0", "approved", "alice",
                version.uploadedAt(), "reviewer", version.publishedAt(), null), audit("PACKAGE_APPROVED"));
        store.addFavorite(new FavoriteRecord("alice", "demo", Instant.now()), audit("FAVORITE_ADDED"));

        GovernanceConfiguration configuration = new GovernanceConfiguration(
                List.of(new TeamDefinition("team-a", "Team A", "desc", "alice", List.of("alice"), "active",
                        Instant.now(), Instant.now())),
                List.of(),
                List.of(new CategoryDefinition("analysis", "Analysis", "", 1, "active", "admin", Instant.now())),
                List.of(),
                List.of(),
                PlatformPolicy.defaults());
        store.updateGovernanceConfiguration(configuration, audit("TEAM_CREATED"));
        GovernanceStore restarted = new GovernanceStore(state, List.of());

        assertThat(restarted.snapshot().configuration().teams()).extracting(TeamDefinition::teamId)
                .containsExactly("team-a");
        assertThat(restarted.snapshot().configuration().categories()).extracting(CategoryDefinition::code)
                .containsExactly("analysis");
        assertThat(restarted.snapshot().versions()).extracting(SkillVersion::skillId).containsExactly("demo");
        assertThat(restarted.snapshot().favorites()).extracting(FavoriteRecord::userId).containsExactly("alice");
        assertThat(restarted.snapshot().audits()).extracting(AuditEvent::action)
                .contains("PACKAGE_APPROVED", "FAVORITE_ADDED", "TEAM_CREATED");
    }

    private AuditEvent audit(String action) {
        return new AuditEvent("audit-" + action, action, "CONFIG", action, "admin", "admin", "req-1",
                Instant.now(), Map.of());
    }
}
