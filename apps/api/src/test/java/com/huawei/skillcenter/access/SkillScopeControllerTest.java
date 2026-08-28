package com.huawei.skillcenter.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.GovernanceConfiguration;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleBinding;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.governance.TeamDefinition;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillScopeControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-24T04:00:00Z");

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mockMvc;
    private GovernanceStore governanceStore;
    private SkillScopeStore scopeStore;

    @BeforeEach
    void setUp() {
        governanceStore = new GovernanceStore(tempDir.resolve("governance-" + System.nanoTime() + ".json"), List.of());
        scopeStore = new SkillScopeStore(tempDir.resolve("scopes-" + System.nanoTime() + ".json"), mapper);
        SkillAuthorizationService service = new SkillAuthorizationService(scopeStore, governanceStore,
                Clock.fixed(NOW, ZoneOffset.UTC));
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillScopeController(service, new ActorResolver()))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();
    }

    @Test
    void getReturnsSafeFallbackProjectionForHistoricalSkillWithoutExplicitScope() throws Exception {
        addVersion(version("pkg-legacy", "skill-legacy", "1.0.0", "published", "uploader"));

        mockMvc.perform(get("/api/v1/admin/skill-access/scopes")
                        .param("skillId", "skill-legacy")
                        .header(ActorResolver.USER_ID_HEADER, "reviewer-1")
                        .header(ActorResolver.ROLE_HEADER, "reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skillId").value("skill-legacy"))
                .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.maintainerUserIds").isArray())
                .andExpect(jsonPath("$.data.maintainerUserIds").isEmpty())
                .andExpect(jsonPath("$.data.declaredBy").doesNotExist())
                .andExpect(jsonPath("$.data.updatedBy").doesNotExist());
    }

    @Test
    void getReturnsExplicitScopeForAuthorizedMaintainerWithoutAuditIdentities() throws Exception {
        addVersion(version("pkg-restricted", "skill-restricted", "1.0.0", "published", "owner"));
        scopeStore.create(scope("skill-restricted", SkillVisibility.RESTRICTED, "", List.of("maintainer-1"), 1));

        mockMvc.perform(get("/api/v1/admin/skill-access/scopes")
                        .param("skillId", "skill-restricted")
                        .header(ActorResolver.USER_ID_HEADER, "maintainer-1")
                        .header(ActorResolver.ROLE_HEADER, "maintainer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visibility").value("RESTRICTED"))
                .andExpect(jsonPath("$.data.maintainerUserIds[0]").value("maintainer-1"))
                .andExpect(jsonPath("$.data.declaredAt").value(containsString("2026-08-24T03:50:00Z")))
                .andExpect(jsonPath("$.data.declaredBy").doesNotExist())
                .andExpect(jsonPath("$.data.updatedBy").doesNotExist());
    }

    @Test
    void getDoesNotExposeScopeMetadataToOrdinaryVisibleUsers() throws Exception {
        addVersion(version("pkg-public", "skill-public", "1.0.0", "published", "owner"));
        scopeStore.create(scope("skill-public", SkillVisibility.PUBLIC, "team-a", List.of("maintainer-1"), 1));

        mockMvc.perform(get("/api/v1/admin/skill-access/scopes")
                        .param("skillId", "skill-public")
                        .header(ActorResolver.USER_ID_HEADER, "viewer-1")
                        .header(ActorResolver.ROLE_HEADER, "viewer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SKILL_NOT_VISIBLE"));
    }

    @Test
    void getAllowsAssignedMaintainerToReadExplicitScopeBeforeFirstVersion() throws Exception {
        configure(new GovernanceConfiguration(
                List.of(team("team-a", List.of("maintainer-1"), "active")),
                List.of(binding("maintainer-1", "maintainer", "team-a", "active")),
                List.of(), List.of(), List.of(), null));
        scopeStore.create(scope("new-skill", SkillVisibility.TEAM, "team-a", List.of(), 1));

        mockMvc.perform(get("/api/v1/admin/skill-access/scopes")
                        .param("skillId", "new-skill")
                        .header(ActorResolver.USER_ID_HEADER, "maintainer-1")
                        .header(ActorResolver.ROLE_HEADER, "maintainer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skillId").value("new-skill"))
                .andExpect(jsonPath("$.data.visibility").value("TEAM"))
                .andExpect(jsonPath("$.data.revision").value(1));
    }

    @Test
    void getReturnsNotFoundForHiddenAndMissingSkillsWithStableCodes() throws Exception {
        addVersion(version("pkg-hidden", "skill-hidden", "1.0.0", "published", "owner"));
        scopeStore.create(scope("skill-hidden", SkillVisibility.RESTRICTED, "", List.of("owner"), 1));

        mockMvc.perform(get("/api/v1/admin/skill-access/scopes")
                        .param("skillId", "skill-hidden")
                        .header(ActorResolver.USER_ID_HEADER, "outsider")
                        .header(ActorResolver.ROLE_HEADER, "viewer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SKILL_NOT_VISIBLE"))
                .andExpect(jsonPath("$.error.message", not(containsString("owner"))))
                .andExpect(jsonPath("$.error.message", not(containsString("RESTRICTED"))));

        mockMvc.perform(get("/api/v1/admin/skill-access/scopes")
                        .param("skillId", "missing-skill")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SKILL_SCOPE_NOT_FOUND"));
    }

    @Test
    void putCreatesAndReplacesScopesWithSafeResponse() throws Exception {
        configure(new GovernanceConfiguration(
                List.of(team("team-a", List.of("member"), "active")),
                List.of(binding("member", "maintainer", "team-a", "active")),
                List.of(), List.of(), List.of(), null));

        mockMvc.perform(put("/api/v1/admin/skill-access/scopes/skill-created")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"TEAM","ownerTeamId":"team-a","maintainerUserIds":[],"revision":0}
                                """)
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.skillId").value("skill-created"))
                .andExpect(jsonPath("$.data.visibility").value("TEAM"))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.declaredBy").doesNotExist());

        mockMvc.perform(put("/api/v1/admin/skill-access/scopes/skill-created")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"RESTRICTED","ownerTeamId":"","maintainerUserIds":["admin-1"],"revision":1}
                                """)
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visibility").value("RESTRICTED"))
                .andExpect(jsonPath("$.data.revision").value(2));
    }

    @Test
    void putReturnsStableErrorsForConflictInvalidAndForbidden() throws Exception {
        configure(new GovernanceConfiguration(List.of(team("team-a", List.of("member"), "active")),
                List.of(), List.of(), List.of(), List.of(), null));
        scopeStore.create(scope("skill-conflict", SkillVisibility.PUBLIC, "", List.of(), 1));

        mockMvc.perform(put("/api/v1/admin/skill-access/scopes/skill-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"PUBLIC","ownerTeamId":"","maintainerUserIds":[],"revision":0}
                                """)
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SKILL_SCOPE_CONFLICT"));

        mockMvc.perform(put("/api/v1/admin/skill-access/scopes/skill-invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"TEAM","ownerTeamId":"missing-team","maintainerUserIds":[],"revision":0}
                                """)
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SKILL_SCOPE_INVALID"))
                .andExpect(jsonPath("$.error.message", not(containsString("missing-team"))));

        mockMvc.perform(put("/api/v1/admin/skill-access/scopes/skill-forbidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"PUBLIC","ownerTeamId":"","maintainerUserIds":[],"revision":0}
                                """)
                        .header(ActorResolver.USER_ID_HEADER, "viewer-1")
                        .header(ActorResolver.ROLE_HEADER, "viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SKILL_MANAGE_FORBIDDEN"));
    }

    private void addVersion(SkillVersion version) {
        governanceStore.createPendingVersion(version,
                new com.huawei.skillcenter.governance.ReviewTask("review-" + version.packageId(),
                        version.packageId(), version.skillId(), version.version(), "approved",
                        version.uploadedBy(), NOW.minusSeconds(60), "reviewer", NOW.minusSeconds(60), null),
                new com.huawei.skillcenter.governance.AuditEvent("audit-" + version.packageId(), "PACKAGE_IMPORTED",
                        "SKILL_VERSION", version.packageId(), "system", "admin", "seed", NOW,
                        Map.of("skillId", version.skillId())));
    }

    private void configure(GovernanceConfiguration configuration) {
        governanceStore.updateGovernanceConfiguration(configuration, null);
    }

    private SkillScope scope(String skillId, SkillVisibility visibility, String ownerTeamId,
                             List<String> maintainerUserIds, int revision) {
        return new SkillScope(skillId, visibility, ownerTeamId, maintainerUserIds, revision,
                "scope-admin", NOW.minusSeconds(600), "scope-admin", NOW.minusSeconds(600));
    }

    private TeamDefinition team(String teamId, List<String> members, String status) {
        return new TeamDefinition(teamId, teamId, "", members.isEmpty() ? "" : members.get(0),
                members, status, NOW.minusSeconds(700), NOW.minusSeconds(700));
    }

    private RoleBinding binding(String userId, String role, String teamId, String status) {
        return new RoleBinding(userId, role, teamId, status, "admin", NOW.minusSeconds(700));
    }

    private SkillVersion version(String packageId, String skillId, String version, String status, String uploadedBy) {
        return new SkillVersion(packageId, skillId, version, status, "a".repeat(64), 1L, "", uploadedBy,
                NOW.minusSeconds(60), "reviewer", NOW.minusSeconds(60), "review-" + packageId, null, null, null,
                null, "low");
    }
}
