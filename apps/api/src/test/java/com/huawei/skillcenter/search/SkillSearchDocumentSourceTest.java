package com.huawei.skillcenter.search;

import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.ReviewTask;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.skill.PageResult;
import com.huawei.skillcenter.skill.SkillMetrics;
import com.huawei.skillcenter.skill.SkillQuery;
import com.huawei.skillcenter.skill.SkillRecord;
import com.huawei.skillcenter.skill.SkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SkillSearchDocumentSourceTest {
    @TempDir
    Path tempDir;

    @Test
    void snapshotExcludesWithdrawnVersionsUsesOnlyAllowListedMetadataAndIsDeterministic() {
        GovernanceStore firstGovernance = governance("first", List.of(
                version("pkg-old", "skill-a", "1.0.0", "published", "2026-01-01T00:00:00Z"),
                version("pkg-new", "skill-a", "2.0.0", "deprecated", "2026-02-01T00:00:00Z"),
                version("pkg-withdrawn", "skill-b", "1.0.0", "withdrawn", "2026-03-01T00:00:00Z")));
        GovernanceStore secondGovernance = governance("second", List.of(
                version("pkg-withdrawn", "skill-b", "1.0.0", "withdrawn", "2026-03-01T00:00:00Z"),
                version("pkg-new", "skill-a", "2.0.0", "deprecated", "2026-02-01T00:00:00Z"),
                version("pkg-old", "skill-a", "1.0.0", "published", "2026-01-01T00:00:00Z")));
        TrackingRepository firstRepository = new TrackingRepository(List.of(record("skill-a", "1.0.0"), record("skill-a", "2.0.0")));
        TrackingRepository secondRepository = new TrackingRepository(List.of(record("skill-a", "2.0.0"), record("skill-a", "1.0.0")));
        SkillSearchScopeProvider scopes = scopes(new SkillSearchScope("skill-a", "TEAM", "team-a"));

        SkillSearchDocumentSnapshot first = new GovernedSkillSearchDocumentSource(firstGovernance, firstRepository, scopes, () -> 9L).snapshot();
        SkillSearchDocumentSnapshot second = new GovernedSkillSearchDocumentSource(secondGovernance, secondRepository, scopes, () -> 9L).snapshot();

        assertThat(first.documents()).extracting(SkillSearchDocument::skillId).containsExactly("skill-a");
        assertThat(first.documents().getFirst())
                .extracting(SkillSearchDocument::latestVersion, SkillSearchDocument::status,
                        SkillSearchDocument::visibility, SkillSearchDocument::ownerTeamId)
                .containsExactly("2.0.0", "deprecated", "TEAM", "team-a");
        assertThat(first.sourceHash()).isEqualTo(second.sourceHash());
        assertThat(first.sourceRevision()).isEqualTo(9L);
        assertThat(first.documents()).isEqualTo(second.documents());
        assertThat(SkillSearchDocument.class.getRecordComponents()).extracting(RecordComponent::getName)
                .containsExactly("skillId", "name", "description", "tags", "team", "category", "status", "risk",
                        "lastUpdated", "publishedAt", "latestVersion", "visibility", "ownerTeamId");
    }

    @Test
    void findRecordUsesSnapshotCacheAndSingleDetailLookupWithoutPageScan() {
        GovernanceStore governance = governance("lookup", List.of(
                version("pkg-a", "skill-a", "1.0.0", "published", "2026-01-01T00:00:00Z")));
        TrackingRepository repository = new TrackingRepository(List.of(record("skill-a", "1.0.0")));
        GovernedSkillSearchDocumentSource source = new GovernedSkillSearchDocumentSource(governance, repository, scopes(), () -> 4L);

        source.snapshot();
        assertThat(source.findRecord("skill-a")).contains(record("skill-a", "1.0.0"));
        assertThat(repository.pageCalls).isEqualTo(1);
        assertThat(repository.detailCalls).isZero();

        GovernedSkillSearchDocumentSource uncached = new GovernedSkillSearchDocumentSource(governance, repository, scopes(), () -> 4L);
        assertThat(uncached.findRecord("skill-a")).contains(record("skill-a", "1.0.0"));
        assertThat(repository.pageCalls).isEqualTo(1);
        assertThat(repository.detailCalls).isEqualTo(1);
    }

    @Test
    void searchOwnedProvidersSupplyScopeAndRevisionAndDefaultToSafeValues() {
        GovernanceStore governance = governance("providers", List.of(
                version("pkg-a", "skill-a", "1.0.0", "published", "2026-01-01T00:00:00Z")));
        TrackingRepository repository = new TrackingRepository(List.of(record("skill-a", "1.0.0")));

        SkillSearchDocumentSnapshot scoped = new GovernedSkillSearchDocumentSource(governance, repository,
                scopes(new SkillSearchScope("skill-a", "RESTRICTED", "team-a")), () -> 42L).snapshot();
        SkillSearchDocumentSnapshot defaults = new GovernedSkillSearchDocumentSource(governance, repository).snapshot();

        assertThat(scoped.documents().getFirst())
                .extracting(SkillSearchDocument::visibility, SkillSearchDocument::ownerTeamId)
                .containsExactly("RESTRICTED", "team-a");
        assertThat(scoped.sourceRevision()).isEqualTo(42L);
        assertThat(defaults.documents().getFirst())
                .extracting(SkillSearchDocument::visibility, SkillSearchDocument::ownerTeamId)
                .containsExactly("PUBLIC", "");
        assertThat(defaults.sourceRevision()).isZero();
    }

    @Test
    void usesRepositoryRiskMetadataWithoutDependingOnGovernedVersionExtensions() {
        GovernanceStore governance = governance("risk", List.of(
                version("pkg-a", "skill-a", "1.0.0", "published", "2026-01-01T00:00:00Z")));
        TrackingRepository repository = new TrackingRepository(List.of(record("skill-a", "1.0.0", "high")));

        SkillSearchDocumentSnapshot snapshot = new GovernedSkillSearchDocumentSource(governance, repository).snapshot();

        assertThat(snapshot.documents().getFirst().risk()).isEqualTo("high");
    }

    @Test
    void snapshotOmitsGovernedVersionsWithoutAnExactVersionRecord() {
        GovernanceStore governance = governance("missing-exact-version", List.of(
                version("pkg-a", "skill-a", "2.0.0", "published", "2026-01-01T00:00:00Z")));
        TrackingRepository repository = new TrackingRepository(List.of(record("skill-a", "1.0.0")));

        SkillSearchDocumentSnapshot snapshot = new GovernedSkillSearchDocumentSource(governance, repository).snapshot();

        assertThat(snapshot.documents()).isEmpty();
    }

    private GovernanceStore governance(String name, List<SkillVersion> versions) {
        GovernanceStore store = new GovernanceStore(tempDir.resolve(name + ".json"), List.of());
        for (SkillVersion version : versions) {
            store.createPendingVersion(version, new ReviewTask("review-" + version.packageId(), version.packageId(),
                    version.skillId(), version.version(), "pending", "admin", version.uploadedAt(), null, null, null),
                    new AuditEvent("audit-" + version.packageId(), "PACKAGE_UPLOADED",
                    "SKILL_VERSION", version.packageId(), "admin", "admin", "request-" + version.packageId(),
                    version.uploadedAt(), Map.of("skillId", version.skillId(), "version", version.version())));
        }
        return store;
    }

    private static SkillVersion version(String packageId, String skillId, String value, String status, String uploadedAt) {
        Instant timestamp = Instant.parse(uploadedAt);
        return new SkillVersion(packageId, skillId, value, status, "a".repeat(64), 1, "ignored-artifact-path",
                "uploader", timestamp, "publisher", timestamp, "review", "ignored-status-reason", null,
                "changer", timestamp);
    }

    private static SkillRecord record(String skillId, String version) {
        return record(skillId, version, "low");
    }

    private static SkillRecord record(String skillId, String version, String risk) {
        return new SkillRecord(skillId, "  Skill " + skillId + "  ", version, "  safe description  ", "tools",
                List.of("zeta", "alpha"), risk, "low", "team", "owner", "icon", "tone", "published",
                "2026-01-02", "2026-01-01", "en", "en", "permission", List.of(), List.of(), List.of(),
                "value", "secret-input", "secret-output", List.of(), new SkillMetrics(0.0, 0, 0, 0, 0));
    }

    private static SkillSearchScopeProvider scopes(SkillSearchScope... values) {
        List<SkillSearchScope> scopes = List.of(values);
        return skillId -> {
                return scopes.stream().filter(scope -> scope.skillId().equals(skillId)).findFirst();
        };
    }

    private static final class TrackingRepository implements SkillRepository {
        private final List<SkillRecord> records;
        private int pageCalls;
        private int detailCalls;

        private TrackingRepository(List<SkillRecord> records) {
            this.records = records;
        }

        @Override
        public PageResult<SkillRecord> findPublished(SkillQuery query) {
            pageCalls++;
            return new PageResult<>(records, query.page(), query.pageSize(), records.size());
        }

        @Override
        public Optional<SkillRecord> findDetail(String skillId) {
            detailCalls++;
            return records.stream().filter(record -> record.id().equals(skillId)).findFirst();
        }
    }
}
