package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.distribution.DistributionAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void pendingReviewSurvivesStoreReload() {
        Path statePath = tempDir.resolve("state.json");
        GovernanceStore first = storeAt(statePath);
        first.createPendingVersion(version("package-1"), review("review-1"), audit("PACKAGE_UPLOADED"));

        GovernanceStore restarted = storeAt(statePath);

        assertThat(restarted.snapshot().versions()).extracting(SkillVersion::status)
                .containsExactly("pending_review");
        assertThat(restarted.snapshot().reviews()).extracting(ReviewTask::reviewId)
                .containsExactly("review-1");
        assertThat(restarted.snapshot().audits()).extracting(AuditEvent::action)
                .containsExactly("PACKAGE_UPLOADED");
    }

    @Test
    void emptyStoreSeedsNothingWhenNoCatalogIsProvided() {
        GovernanceStore store = storeAt(tempDir.resolve("empty.json"));

        assertThat(store.snapshot().versions()).isEmpty();
        assertThat(store.snapshot().reviews()).isEmpty();
        assertThat(store.snapshot().installations()).isEmpty();
    }

    @Test
    void governanceMutationsPreserveIssuedDistributionAuthorizations() {
        GovernanceStore store = storeAt(tempDir.resolve("authorization.json"));
        store.addAuthorization(new DistributionAuthorization("token-1", "digest-1", "demo-skill", "1.0.0",
                "installation-1", "alice", "codex", "1.0.0", "cli",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-17T00:15:00Z"),
                null, null, null));
        store.createPendingVersion(version("package-1"), review("review-1"), audit("PACKAGE_UPLOADED"));

        assertThat(store.snapshot().authorizations()).extracting(DistributionAuthorization::tokenId)
                .containsExactly("token-1");
    }

    @Test
    void lifecycleMetadataAndFavoritesSurviveReload() {
        Path statePath = tempDir.resolve("lifecycle.json");
        GovernanceStore store = storeAt(statePath);
        SkillVersion published = new SkillVersion("package-1", "demo-skill", "1.0.0", "published",
                "a".repeat(64), 123, "data/packages/package-1.zip", "uploader",
                Instant.parse("2026-08-17T00:00:00Z"), "reviewer",
                Instant.parse("2026-08-17T00:01:00Z"), "review-1");
        store.createPendingVersion(published, review("review-1"), audit("PACKAGE_UPLOADED"));
        SkillVersion deprecated = new SkillVersion(published.packageId(), published.skillId(), published.version(),
                "deprecated", published.sha256(), published.sizeBytes(), published.artifactPath(), published.uploadedBy(),
                published.uploadedAt(), published.publishedBy(), published.publishedAt(), published.reviewId(),
                "security issue", "1.1.0", "admin", Instant.parse("2026-08-17T00:02:00Z"));
        store.updateVersion(deprecated, audit("VERSION_DEPRECATED"));
        store.addFavorite(new FavoriteRecord("alice", "demo-skill", Instant.parse("2026-08-17T00:03:00Z")),
                audit("FAVORITE_ADDED"));

        GovernanceStore restarted = storeAt(statePath);

        assertThat(restarted.snapshot().versions()).singleElement().satisfies(version -> {
            assertThat(version.status()).isEqualTo("deprecated");
            assertThat(version.statusReason()).isEqualTo("security issue");
            assertThat(version.replacementVersion()).isEqualTo("1.1.0");
            assertThat(version.statusChangedBy()).isEqualTo("admin");
        });
        assertThat(restarted.snapshot().favorites()).extracting(FavoriteRecord::userId)
                .containsExactly("alice");
    }

    @Test
    void favoritesAreIdempotentAndScopedToUser() {
        GovernanceStore store = storeAt(tempDir.resolve("favorites.json"));
        FavoriteRecord favorite = new FavoriteRecord("alice", "demo-skill", Instant.parse("2026-08-17T00:00:00Z"));

        store.addFavorite(favorite, null);
        store.addFavorite(favorite, null);
        store.addFavorite(new FavoriteRecord("bob", "demo-skill", favorite.createdAt()), null);
        store.removeFavorite("alice", "demo-skill", null);
        store.removeFavorite("alice", "demo-skill", null);

        assertThat(store.favoritesForUser("alice")).isEmpty();
        assertThat(store.favoritesForUser("bob")).extracting(FavoriteRecord::userId)
                .containsExactly("bob");
    }

    private GovernanceStore storeAt(Path path) {
        return new GovernanceStore(path, List.of());
    }

    private SkillVersion version(String packageId) {
        return new SkillVersion(packageId, "demo-skill", "1.0.0", "pending_review", "a".repeat(64),
                123, "data/packages/" + packageId + ".zip", "uploader", Instant.parse("2026-08-17T00:00:00Z"),
                null, null, "review-1");
    }

    private ReviewTask review(String reviewId) {
        return new ReviewTask(reviewId, "package-1", "demo-skill", "1.0.0", "pending", "uploader",
                Instant.parse("2026-08-17T00:00:00Z"), null, null, null);
    }

    private AuditEvent audit(String action) {
        return new AuditEvent("audit-1", action, "skill-package", "package-1", "uploader", "maintainer",
                "request-1", Instant.parse("2026-08-17T00:00:00Z"), Map.of("skillId", "demo-skill", "version", "1.0.0"));
    }
}
