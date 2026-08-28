package com.huawei.skillcenter.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.distribution.ArtifactDownloadService;
import com.huawei.skillcenter.distribution.ArtifactPackageService;
import com.huawei.skillcenter.distribution.DistributionAuthorizationService;
import com.huawei.skillcenter.distribution.DistributionResponse;
import com.huawei.skillcenter.distribution.DistributionService;
import com.huawei.skillcenter.distribution.InstallationRequest;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceConfiguration;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationService;
import com.huawei.skillcenter.governance.ReviewService;
import com.huawei.skillcenter.governance.ReviewTask;
import com.huawei.skillcenter.governance.RoleBinding;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.governance.TeamDefinition;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import com.huawei.skillcenter.packageupload.PackageValidationResult;
import com.huawei.skillcenter.packageupload.StoredPackage;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessmentStore;
import com.huawei.skillcenter.release.ReleaseEnvironment;
import com.huawei.skillcenter.release.ReleaseGateSnapshot;
import com.huawei.skillcenter.release.ReleaseRecord;
import com.huawei.skillcenter.release.ReleaseRecordStore;
import com.huawei.skillcenter.release.ReleaseRequest;
import com.huawei.skillcenter.release.ReleaseService;
import com.huawei.skillcenter.release.ReleaseTarget;
import com.huawei.skillcenter.relationship.SkillRelation;
import com.huawei.skillcenter.relationship.SkillRelationImpact;
import com.huawei.skillcenter.relationship.SkillRelationQuery;
import com.huawei.skillcenter.relationship.SkillRelationService;
import com.huawei.skillcenter.relationship.SkillRelationStore;
import com.huawei.skillcenter.relationship.SkillRelationType;
import com.huawei.skillcenter.skill.PageResult;
import com.huawei.skillcenter.skill.SkillCatalogService;
import com.huawei.skillcenter.skill.SkillController;
import com.huawei.skillcenter.skill.SkillMetrics;
import com.huawei.skillcenter.skill.SkillQuery;
import com.huawei.skillcenter.skill.SkillRecord;
import com.huawei.skillcenter.skill.SkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillAuthorizationBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-08-24T05:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void catalogControllerUsesActorAwareVisibilityAndKeepsLegacyPublicSkillVisible() throws Exception {
        BoundaryFixture fixture = new BoundaryFixture(tempDir.resolve("catalog"));
        fixture.addRepositorySkill(skill("legacy-public", "1.0.0", "published", "team-legacy", "legacy-owner"));
        fixture.addRepositorySkill(skill("team-skill", "1.0.0", "published", "team-a", "team-owner"));
        fixture.addRepositorySkill(skill("restricted-skill", "1.0.0", "published", "secret-team", "secret-owner"));
        fixture.addVersion(version("pkg-team", "team-skill", "1.0.0", "published", "team-maintainer"));
        fixture.addVersion(version("pkg-restricted", "restricted-skill", "1.0.0", "published", "named-maintainer"));
        fixture.configure(config(
                List.of(team("team-a", List.of("team-member", "team-maintainer"), "active")),
                List.of(
                        binding("team-member", "viewer", "team-a", "active"),
                        binding("team-maintainer", "maintainer", "team-a", "active")
                )));
        fixture.scopeStore().create(scope("team-skill", SkillVisibility.TEAM, "team-a", List.of(), 1));
        fixture.scopeStore().create(scope("restricted-skill", SkillVisibility.RESTRICTED, "", List.of("named-maintainer"), 1));

        MockMvc mockMvc = fixture.skillController();

        mockMvc.perform(get("/api/v1/skills")
                        .header(ActorResolver.USER_ID_HEADER, "outsider")
                        .header(ActorResolver.ROLE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("legacy-public"));

        mockMvc.perform(get("/api/v1/skills/team-skill")
                        .header(ActorResolver.USER_ID_HEADER, "outsider")
                        .header(ActorResolver.ROLE_HEADER, "viewer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SKILL_NOT_VISIBLE"));

        mockMvc.perform(get("/api/v1/skills/restricted-skill/content")
                        .header(ActorResolver.USER_ID_HEADER, "outsider")
                        .header(ActorResolver.ROLE_HEADER, "viewer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SKILL_NOT_VISIBLE"));

        mockMvc.perform(get("/api/v1/skills/team-skill")
                        .header(ActorResolver.USER_ID_HEADER, "team-member")
                        .header(ActorResolver.ROLE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("team-skill"));

        mockMvc.perform(get("/api/v1/skills/restricted-skill/content")
                        .header(ActorResolver.USER_ID_HEADER, "named-maintainer")
                        .header(ActorResolver.ROLE_HEADER, "maintainer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(""));

        mockMvc.perform(get("/api/v1/skills/restricted-skill")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("restricted-skill"));
    }

    @Test
    void submitReleaseInstallAndDownloadUseScopeAuthorizationBoundaries() {
        BoundaryFixture fixture = new BoundaryFixture(tempDir.resolve("workflow"));
        fixture.addRepositorySkill(skill("team-skill", "1.0.0", "published", "team-a", "team-owner"));
        fixture.addRepositorySkill(skill("restricted-skill", "1.0.0", "published", "secret-team", "secret-owner"));
        fixture.addRepositorySkill(skill("legacy-public", "1.0.0", "published", "legacy-team", "legacy-owner"));
        fixture.addVersion(version("pkg-team", "team-skill", "1.0.0", "published", "team-maintainer"));
        fixture.addVersion(version("pkg-restricted", "restricted-skill", "1.0.0", "published", "named-maintainer"));
        fixture.addVersion(version("pkg-legacy", "legacy-public", "1.0.0", "published", "legacy-owner"));
        fixture.configure(config(
                List.of(
                        team("team-a", List.of("team-member", "team-maintainer"), "active"),
                        team("team-b", List.of("other-maintainer"), "active")
                ),
                List.of(
                        binding("team-member", "viewer", "team-a", "active"),
                        binding("team-maintainer", "maintainer", "team-a", "active"),
                        binding("other-maintainer", "maintainer", "team-b", "active")
                )));
        fixture.scopeStore().create(scope("team-skill", SkillVisibility.TEAM, "team-a", List.of(), 1));
        fixture.scopeStore().create(scope("restricted-skill", SkillVisibility.RESTRICTED, "", List.of("named-maintainer"), 1));

        ReviewService reviews = fixture.reviewService();
        assertThatThrownBy(() -> reviews.submitValidatedPackage(packageResult("team-skill", "1.1.0"),
                new StoredPackage("pkg-team-submit", "C:/packages/pkg-team-submit.zip"),
                actor("other-maintainer", "maintainer"), "submit-team-forbidden"))
                .isInstanceOf(SkillManageForbiddenException.class);

        ReviewTask submitted = reviews.submitValidatedPackage(packageResult("team-skill", "1.1.0"),
                new StoredPackage("pkg-team-submit-ok", "C:/packages/pkg-team-submit-ok.zip"),
                actor("team-maintainer", "maintainer"), "submit-team-ok");
        assertThat(submitted.status()).isEqualTo("pending_review");

        ArtifactPackageService artifacts = mock(ArtifactPackageService.class);
        when(artifacts.metadata("team-skill", "1.0.0", fixture.versionFor("team-skill", "1.0.0")))
                .thenReturn(new ArtifactPackageService.ArtifactMetadata("a".repeat(64), 128));
        when(artifacts.metadata("legacy-public", "1.0.0", fixture.versionFor("legacy-public", "1.0.0")))
                .thenReturn(new ArtifactPackageService.ArtifactMetadata("b".repeat(64), 96));
        when(artifacts.generate("team-skill", "1.0.0"))
                .thenReturn(new ArtifactPackageService.GeneratedArtifact(new ByteArrayResource("zip".getBytes()),
                        "c".repeat(64), 3, "team-skill", "1.0.0"));
        com.huawei.skillcenter.release.ReleaseAdmissionService admission = mock(com.huawei.skillcenter.release.ReleaseAdmissionService.class);
        when(admission.requireDownloadable("team-skill", "1.0.0")).thenReturn(null);
        when(admission.requireDownloadable("legacy-public", "1.0.0")).thenReturn(null);

        DistributionService distribution = fixture.distributionService(artifacts, admission);
        InstallationService installations = fixture.installationService(distribution);

        assertThatThrownBy(() -> installations.createManifest("team-skill",
                new InstallationRequest("codex", "1.0.0", "cli"),
                actor("outsider", "viewer"), "install-hidden"))
                .isInstanceOf(SkillNotVisibleException.class);
        assertThat(fixture.governanceStore().snapshot().authorizations()).isEmpty();

        DistributionResponse teamInstall = installations.createManifest("team-skill",
                new InstallationRequest("codex", "1.0.0", "cli"),
                actor("team-member", "viewer"), "install-team");
        assertThat(teamInstall.skill().id()).isEqualTo("team-skill");
        assertThat(teamInstall.authorization().token()).isNotBlank();

        DistributionResponse legacyInstall = installations.createManifest("legacy-public",
                new InstallationRequest("codex", "1.0.0", "manual-zip"),
                actor("outsider", "viewer"), "install-legacy");
        assertThat(legacyInstall.skill().id()).isEqualTo("legacy-public");

        DistributionAuthorizationService authorizations = fixture.distributionAuthorizations();
        ArtifactDownloadService downloads = fixture.artifactDownloadService(authorizations, artifacts, admission);
        String token = teamInstall.authorization().token();

        assertThatThrownBy(() -> downloads.download("team-skill", "1.0.0", token, actor("outsider", "viewer")))
                .isInstanceOf(SkillNotVisibleException.class);
        assertThat(fixture.governanceStore().snapshot().authorizations()).filteredOn(authorization ->
                        "team-skill".equals(authorization.skillId()))
                .singleElement()
                .satisfies(authorization -> assertThat(authorization.consumedAt()).isNull());

        ArtifactDownloadService.DownloadedArtifact downloaded = downloads.download("team-skill", "1.0.0", token,
                actor("team-member", "viewer"));
        assertThat(downloaded.sha256()).isEqualTo("c".repeat(64));
        assertThat(fixture.governanceStore().snapshot().authorizations()).filteredOn(authorization ->
                        "team-skill".equals(authorization.skillId()))
                .singleElement()
                .satisfies(authorization -> assertThat(authorization.consumedAt()).isNotNull());

        ReleaseService releases = fixture.releaseService();
        assertThatThrownBy(() -> releases.request(
                new ReleaseRequest("team-skill", "1.0.0", ReleaseEnvironment.STAGING, "", "idem-forbidden"),
                actor("other-maintainer", "maintainer"), "release-forbidden"))
                .isInstanceOf(SkillManageForbiddenException.class);

        ReleaseRecord requested = releases.request(
                new ReleaseRequest("team-skill", "1.0.0", ReleaseEnvironment.STAGING, "", "idem-team"),
                actor("team-maintainer", "maintainer"), "release-team");
        assertThat(requested.skillId()).isEqualTo("team-skill");

        ReleaseRecord adminRequested = releases.request(
                new ReleaseRequest("restricted-skill", "1.0.0", ReleaseEnvironment.STAGING, "", "idem-admin"),
                actor("admin-1", "admin"), "release-admin");
        assertThat(adminRequested.skillId()).isEqualTo("restricted-skill");
    }

    @Test
    void hiddenRelationTargetsDoNotLeakThroughListsOrImpact() {
        BoundaryFixture fixture = new BoundaryFixture(tempDir.resolve("relations"));
        fixture.addVersion(version("pkg-root", "root-public", "1.0.0", "published", "root-owner"));
        fixture.addVersion(version("pkg-hidden", "hidden-mid", "1.0.0", "published", "hidden-owner"));
        fixture.addVersion(version("pkg-leaf", "visible-leaf", "1.0.0", "published", "leaf-owner"));
        fixture.scopeStore().create(scope("hidden-mid", SkillVisibility.RESTRICTED, "", List.of("hidden-owner"), 1));

        SkillRelationService relations = fixture.skillRelationService();
        relations.create(new com.huawei.skillcenter.relationship.SkillRelationRequest(
                        "hidden-mid", "1.0.0", "root-public", "1.0.0", SkillRelationType.DEPENDS_ON),
                actor("admin-1", "admin"), "create-hidden-root");
        relations.create(new com.huawei.skillcenter.relationship.SkillRelationRequest(
                        "visible-leaf", "1.0.0", "hidden-mid", "1.0.0", SkillRelationType.DEPENDS_ON),
                actor("admin-1", "admin"), "create-leaf-hidden");

        assertThatThrownBy(() -> relations.create(new com.huawei.skillcenter.relationship.SkillRelationRequest(
                        "visible-leaf", "1.0.0", "hidden-mid", "1.0.0", SkillRelationType.COMPOSES),
                actor("leaf-owner", "maintainer"), "create-forbidden-hidden"))
                .isInstanceOf(SkillNotVisibleException.class);

        List<SkillRelation> visibleRelations = relations.list(new SkillRelationQuery(
                "visible-leaf", "1.0.0", null, null, null, 5, 100), actor("leaf-owner", "maintainer"));
        assertThat(visibleRelations).isEmpty();

        SkillRelationImpact impact = relations.impact("root-public", "1.0.0",
                new SkillRelationQuery(null, null, null, null, null, 5, 100), actor("leaf-owner", "maintainer"));
        assertThat(impact.nodes()).isEmpty();
        assertThat(impact.nodes()).extracting(node -> node.skillId()).doesNotContain("hidden-mid", "visible-leaf");
    }

    private static PackageValidationResult packageResult(String skillId, String version) {
        return new PackageValidationResult(true, skillId, version, "a".repeat(64), 42, List.of());
    }

    private static Actor actor(String userId, String role) {
        return new Actor(userId, role);
    }

    private static SkillRecord skill(String skillId, String version, String status, String team, String owner) {
        return new SkillRecord(skillId, skillId, version, skillId + " description", "other", List.of(), "low", "low",
                team, owner, "", "blue", status, "2026-08-24", "2026-08-24", "Java", "Java", "read",
                List.of(), List.of(), List.of(), "", "", "", List.of(), new SkillMetrics(0, 0, 0, 0, 0));
    }

    private static SkillVersion version(String packageId, String skillId, String version, String status, String uploadedBy) {
        return new SkillVersion(packageId, skillId, version, status, "a".repeat(64), 10L, "",
                uploadedBy, NOW.minusSeconds(300), "reviewer", NOW.minusSeconds(240), "review-" + packageId,
                null, null, null, null, "low");
    }

    private static SkillScope scope(String skillId, SkillVisibility visibility, String ownerTeamId,
                                    List<String> maintainers, int revision) {
        return new SkillScope(skillId, visibility, ownerTeamId, maintainers, revision,
                "scope-admin", NOW.minusSeconds(600), "scope-admin", NOW.minusSeconds(600));
    }

    private static GovernanceConfiguration config(List<TeamDefinition> teams, List<RoleBinding> bindings) {
        return new GovernanceConfiguration(teams, bindings, List.of(), List.of(), List.of(), null);
    }

    private static TeamDefinition team(String teamId, List<String> members, String status) {
        return new TeamDefinition(teamId, teamId, "", members.isEmpty() ? "" : members.get(0),
                members, status, NOW.minusSeconds(900), NOW.minusSeconds(900));
    }

    private static RoleBinding binding(String userId, String role, String teamId, String status) {
        return new RoleBinding(userId, role, teamId, status, "admin", NOW.minusSeconds(900));
    }

    private static final class BoundaryFixture {
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final GovernanceStore governanceStore;
        private final SkillScopeStore scopeStore;
        private final ReleaseRecordStore releaseStore;
        private final SkillRelationStore relationStore;
        private final Clock clock;
        private final Map<String, SkillRecord> repositoryRecords = new LinkedHashMap<>();
        private SkillAuthorizationService authorizationService;

        private BoundaryFixture(Path root) {
            this.clock = Clock.fixed(NOW, ZoneOffset.UTC);
            this.governanceStore = new GovernanceStore(root.resolve("governance.json"), List.of());
            this.scopeStore = new SkillScopeStore(root.resolve("scopes.json"), mapper);
            this.releaseStore = new ReleaseRecordStore(mapper, root.resolve("releases.json").toString());
            this.relationStore = new SkillRelationStore(mapper, root.resolve("relations.json").toString());
        }

        private void addRepositorySkill(SkillRecord record) {
            repositoryRecords.put(record.id(), record);
        }

        private void addVersion(SkillVersion version) {
            governanceStore.createPendingVersion(version,
                    new ReviewTask("review-" + version.packageId(), version.packageId(), version.skillId(),
                            version.version(), "approved", version.uploadedBy(), version.uploadedAt(),
                            "reviewer", version.uploadedAt(), null),
                    new AuditEvent("audit-" + version.packageId(), "PACKAGE_IMPORTED", "SKILL_VERSION",
                            version.packageId(), "system", "admin", "seed", NOW,
                            Map.of("skillId", version.skillId(), "version", version.version())));
        }

        private void configure(GovernanceConfiguration configuration) {
            governanceStore.updateGovernanceConfiguration(configuration, null);
        }

        private SkillScopeStore scopeStore() {
            return scopeStore;
        }

        private GovernanceStore governanceStore() {
            return governanceStore;
        }

        private SkillVersion versionFor(String skillId, String version) {
            return governanceStore.snapshot().versions().stream()
                    .filter(candidate -> skillId.equals(candidate.skillId()) && version.equals(candidate.version()))
                    .findFirst()
                    .orElseThrow();
        }

        private SkillCatalogService catalogService() {
            return new SkillCatalogService(repository(), governanceStore, mapper, skillAuthorizationService());
        }

        private MockMvc skillController() {
            return MockMvcBuilders.standaloneSetup(new SkillController(catalogService()))
                    .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                    .build();
        }

        private ReviewService reviewService() {
            return new ReviewService(governanceStore, (skillId, version) -> { }, null, skillAuthorizationService());
        }

        private DistributionAuthorizationService distributionAuthorizations() {
            return new DistributionAuthorizationService(governanceStore, clock);
        }

        private DistributionService distributionService(ArtifactPackageService artifacts,
                                                       com.huawei.skillcenter.release.ReleaseAdmissionService admission) {
            return new DistributionService(catalogService(), governanceStore, artifacts, admission,
                    skillAuthorizationService(), "https://skill-center.internal/artifacts");
        }

        private InstallationService installationService(DistributionService distributionService) {
            return new InstallationService(distributionService, distributionAuthorizations(), governanceStore);
        }

        private ArtifactDownloadService artifactDownloadService(DistributionAuthorizationService authorizations,
                                                                ArtifactPackageService artifacts,
                                                                com.huawei.skillcenter.release.ReleaseAdmissionService admission) {
            return new ArtifactDownloadService(governanceStore, authorizations, artifacts, admission,
                    skillAuthorizationService());
        }

        private ReleaseService releaseService() {
            com.huawei.skillcenter.governance.QualityReleaseGate gate = mock(com.huawei.skillcenter.governance.QualityReleaseGate.class);
            when(gate.evaluate("team-skill", "1.0.0")).thenReturn(ReleaseGateSnapshot.passed(NOW));
            when(gate.evaluate("restricted-skill", "1.0.0")).thenReturn(ReleaseGateSnapshot.passed(NOW));
            ReleaseTarget target = mock(ReleaseTarget.class);
            OptimizationExperimentAssessmentStore assessments = mock(OptimizationExperimentAssessmentStore.class);
            return new ReleaseService(governanceStore, gate, releaseStore, target, assessments,
                    skillAuthorizationService(), clock);
        }

        private SkillRelationService skillRelationService() {
            return new SkillRelationService(governanceStore, relationStore, releaseStore, skillAuthorizationService(), clock);
        }

        private SkillAuthorizationService skillAuthorizationService() {
            if (authorizationService == null) {
                authorizationService = new SkillAuthorizationService(scopeStore, governanceStore, clock);
            }
            return authorizationService;
        }

        private SkillRepository repository() {
            return new SkillRepository() {
                @Override
                public PageResult<SkillRecord> findPublished(SkillQuery query) {
                    List<SkillRecord> all = new ArrayList<>(repositoryRecords.values());
                    int from = Math.min((query.page() - 1) * query.pageSize(), all.size());
                    int to = Math.min(from + query.pageSize(), all.size());
                    return new PageResult<>(all.subList(from, to), query.page(), query.pageSize(), all.size());
                }

                @Override
                public Optional<SkillRecord> findDetail(String skillId) {
                    return Optional.ofNullable(repositoryRecords.get(skillId));
                }
            };
        }
    }
}
