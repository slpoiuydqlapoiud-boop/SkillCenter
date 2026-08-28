package com.huawei.skillcenter.search;

import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillSearchIndexControllerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        JsonSkillSearchIndex index = new JsonSkillSearchIndex();
        mockMvc = mockMvc(new SkillSearchRefreshCoordinator(index, new TestSource()));
    }

    private MockMvc mockMvc(SkillSearchRefreshCoordinator coordinator) {
        return MockMvcBuilders.standaloneSetup(new SkillSearchIndexController(coordinator, new ActorResolver()))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();
    }

    @Test
    void statusIsAdminOnlyAndContainsOnlyBoundedOperationalFields() throws Exception {
        mockMvc.perform(get("/api/v1/admin/search/index/status")
                        .header(ActorResolver.USER_ID_HEADER, "viewer-1")
                        .header(ActorResolver.ROLE_HEADER, "viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/search/index/status")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backend").value("json"))
                .andExpect(jsonPath("$.data.status").value("NOT_READY"))
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.documentCount").value(0))
                .andExpect(jsonPath("$.data.indexedAt").exists())
                .andExpect(jsonPath("$.data.reasonCode").exists())
                .andExpect(jsonPath("$.data.sourceHash").doesNotExist())
                .andExpect(jsonPath("$.data.markdown").doesNotExist())
                .andExpect(jsonPath("$.data.credentials").doesNotExist());
    }

    @Test
    void rebuildRequiresAdminAndExplicitRequestId() throws Exception {
        mockMvc.perform(post("/api/v1/admin/search/index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(ActorResolver.USER_ID_HEADER, "viewer-1")
                        .header(ActorResolver.ROLE_HEADER, "viewer")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-viewer"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/search/index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SEARCH_INDEX_REQUEST_ID_REQUIRED"));
    }

    @Test
    void rebuildReturnsStableConflictAndSuccessfulBoundedResult() throws Exception {
        mockMvc.perform(post("/api/v1/admin/search/index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSourceHash\":\"different-hash\"}")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEARCH_INDEX_SOURCE_CONFLICT"))
                .andExpect(jsonPath("$.error.message", not(containsString("different-hash"))));

        mockMvc.perform(post("/api/v1/admin/search/index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSourceHash\":\"safe-source-hash\"}")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backend").value("json"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.documentCount").value(1))
                .andExpect(jsonPath("$.data.reasonCode").value(""))
                .andExpect(jsonPath("$.data.sourceHash").doesNotExist())
                .andExpect(jsonPath("$.requestId").value("req-rebuild"));
    }

    @Test
    void rejectsUnknownAndOversizedRebuildFields() throws Exception {
        mockMvc.perform(post("/api/v1/admin/search/index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unexpected\":true}")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVENT_SCHEMA_INVALID"));

        mockMvc.perform(post("/api/v1/admin/search/index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSourceHash\":\"" + "x".repeat(257) + "\"}")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-long"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SEARCH_INDEX_INVALID_REQUEST"));
    }

    @Test
    void rebuildFailureUsesStableErrorWithoutExceptionText() throws Exception {
        SkillSearchDocumentSource failing = new SkillSearchDocumentSource() {
            @Override
            public SkillSearchDocumentSnapshot snapshot() {
                throw new IllegalStateException("secret source path");
            }

            @Override
            public Optional<com.huawei.skillcenter.skill.SkillRecord> findRecord(String skillId) {
                return Optional.empty();
            }
        };
        mockMvc = mockMvc(new SkillSearchRefreshCoordinator(new JsonSkillSearchIndex(), failing));

        mockMvc.perform(post("/api/v1/admin/search/index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-failed"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SEARCH_INDEX_REBUILD_FAILED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("secret source path"))));
    }

    @Test
    void probeIsAdminOnlyAndReturnsSafeMetadata() throws Exception {
        SkillSearchConnectivityProbeService probe = mock(SkillSearchConnectivityProbeService.class);
        when(probe.probe(any(), eq("req-probe"))).thenReturn(new SkillSearchProbeResult(
                "opensearch", "REACHABLE", "SEARCH_INDEX_PROBE_OK", 200, 4, Instant.parse("2026-08-28T00:00:00Z")));
        mockMvc = mockMvc(new SkillSearchRefreshCoordinator(new JsonSkillSearchIndex(), new TestSource()), probe);

        mockMvc.perform(post("/api/v1/admin/search/index/probe")
                        .header(ActorResolver.USER_ID_HEADER, "viewer-1")
                        .header(ActorResolver.ROLE_HEADER, "viewer")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-probe"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/search/index/probe")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REACHABLE"))
                .andExpect(jsonPath("$.data.reasonCode").value("SEARCH_INDEX_PROBE_OK"))
                .andExpect(jsonPath("$.data.endpoint").doesNotExist())
                .andExpect(jsonPath("$.data.body").doesNotExist())
                .andExpect(jsonPath("$.requestId").value("req-probe"));
    }

    @Test
    void probeRemoteFailureUsesStableUnavailableError() throws Exception {
        SkillSearchConnectivityProbeService probe = mock(SkillSearchConnectivityProbeService.class);
        when(probe.probe(any(), eq("req-error"))).thenThrow(
                new SkillSearchIndexRemoteException("SEARCH_INDEX_UPSTREAM_UNAVAILABLE"));
        mockMvc = mockMvc(new SkillSearchRefreshCoordinator(new JsonSkillSearchIndex(), new TestSource()), probe);

        mockMvc.perform(post("/api/v1/admin/search/index/probe")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-error"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SEARCH_INDEX_UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message", not(containsString("endpoint"))));
    }

    private MockMvc mockMvc(SkillSearchRefreshCoordinator coordinator, SkillSearchConnectivityProbeService probe) {
        return MockMvcBuilders.standaloneSetup(new SkillSearchIndexController(coordinator, new ActorResolver(), probe))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();
    }

    private static final class TestSource implements SkillSearchDocumentSource {
        @Override
        public SkillSearchDocumentSnapshot snapshot() {
            return new SkillSearchDocumentSnapshot(List.of(new SkillSearchDocument(
                    "safe-skill", "Safe skill", "bounded summary", List.of("safe"), "platform", "other",
                    "published", "low", Instant.parse("2026-08-27T00:00:00Z"),
                    Instant.parse("2026-08-27T00:00:00Z"), "1.0.0", "PUBLIC", "")),
                    "safe-source-hash", 7L);
        }

        @Override
        public Optional<com.huawei.skillcenter.skill.SkillRecord> findRecord(String skillId) {
            return Optional.empty();
        }
    }
}
