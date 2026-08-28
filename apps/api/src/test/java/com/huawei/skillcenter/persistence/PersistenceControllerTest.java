package com.huawei.skillcenter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersistenceControllerTest {
    private static final String REQUEST_ID = "request-task-4";
    private static final Instant CHECKED_AT = Instant.parse("2026-08-25T00:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-08-24T23:59:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private PersistenceControlService service;
    private ActorResolver actorResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PersistenceControlService.class);
        actorResolver = mock(ActorResolver.class);
        when(actorResolver.resolve(any())).thenReturn(new Actor("admin-user", "admin"));
        mockMvc = MockMvcBuilders.standaloneSetup(new PersistenceController(service, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();
    }

    @Test
    void adminCanReadReadyStatusWithRequestIdAndOnlyAllowListedMetadata() throws Exception {
        when(service.status()).thenReturn(new PersistenceControlService.PersistenceStatusView(
                "READY",
                List.of(new PersistenceControlService.PersistenceArtifactStatusView(
                        "governance-state", "READY", 1, 1, 128L, "a".repeat(64), 3L,
                        CHECKED_AT, ""))));

        mockMvc.perform(get("/api/v1/admin/persistence/status")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.overall").value("READY"))
                .andExpect(jsonPath("$.data.artifacts[0].artifactId").value("governance-state"))
                .andExpect(jsonPath("$.data.artifacts[0].sha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.artifacts[0].storagePath").doesNotExist())
                .andExpect(jsonPath("$.data.artifacts[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.artifacts[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.artifacts[0].prompt").doesNotExist())
                .andExpect(jsonPath("$.data.artifacts[0].trace").doesNotExist())
                .andExpect(jsonPath("$.data.artifacts[0].credential").doesNotExist())
                .andExpect(jsonPath("$.data.artifacts[0].token").doesNotExist())
                .andExpect(jsonPath("$.data.artifacts[0].cause").doesNotExist());
    }

    @Test
    void statusExposesDegradedAndFailClosedTransitionsWithoutChangingEnvelope() throws Exception {
        when(service.status()).thenReturn(new PersistenceControlService.PersistenceStatusView(
                "DEGRADED", List.of()));
        mockMvc.perform(get("/api/v1/admin/persistence/status")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall").value("DEGRADED"));

        when(service.status()).thenReturn(new PersistenceControlService.PersistenceStatusView(
                "FAIL_CLOSED", List.of()));
        mockMvc.perform(get("/api/v1/admin/persistence/status")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall").value("FAIL_CLOSED"));
    }

    @Test
    void nonAdminReceivesExistingForbiddenEnvelope() throws Exception {
        when(actorResolver.resolve(any())).thenReturn(new Actor("viewer-user", "viewer"));

        mockMvc.perform(get("/api/v1/admin/persistence/status")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));

        verify(service, never()).status();
    }

    @Test
    void snapshotCreateListGetAndPreflightReturnMetadataOnly() throws Exception {
        PersistenceControlService.PersistenceSnapshotView snapshot = snapshot();
        when(service.createSnapshot()).thenReturn(snapshot);
        when(service.listSnapshots()).thenReturn(List.of(snapshot));
        when(service.getSnapshot("snapshot-1")).thenReturn(snapshot);
        when(service.restorePreflight("snapshot-1"))
                .thenReturn(new PersistenceControlService.PersistencePreflightView(
                        "snapshot-1", "READY", "", 1));

        mockMvc.perform(post("/api/v1/admin/persistence/snapshots")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshotId").value("snapshot-1"))
                .andExpect(jsonPath("$.data.artifacts[0].relativePath").value("artifacts/governance-state/data"))
                .andExpect(jsonPath("$.data.artifacts[0].kind").value("FILE"))
                .andExpect(jsonPath("$.data.artifacts[0].availability").value("READY"))
                .andExpect(jsonPath("$.data.storagePath").doesNotExist())
                .andExpect(jsonPath("$.data.downloadUrl").doesNotExist())
                .andExpect(jsonPath("$.data.payload").doesNotExist())
                .andExpect(jsonPath("$.data.prompt").doesNotExist())
                .andExpect(jsonPath("$.data.trace").doesNotExist())
                .andExpect(jsonPath("$.data.credential").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.cause").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/persistence/snapshots")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].snapshotId").value("snapshot-1"));

        mockMvc.perform(get("/api/v1/admin/persistence/snapshots/snapshot-1")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manifestSha256").value("b".repeat(64)));

        mockMvc.perform(post("/api/v1/admin/persistence/snapshots/snapshot-1/restore-preflight")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.reasonCode").value(""))
                .andExpect(jsonPath("$.data.artifactCount").value(1))
                .andExpect(content().string(not(containsString("storagePath"))));

        verify(service).createSnapshot();
        verify(service).listSnapshots();
        verify(service).getSnapshot("snapshot-1");
        verify(service).restorePreflight("snapshot-1");
    }

    @Test
    void getDoesNotInvokeSnapshotCreation() throws Exception {
        when(service.listSnapshots()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/persistence/snapshots")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        verify(service).listSnapshots();
        verify(service, never()).createSnapshot();
    }

    @Test
    void mapsStableNotFoundAndInvalidPersistenceErrorsWithoutRawCause() throws Exception {
        when(service.getSnapshot("missing"))
                .thenThrow(new PersistenceControlException("PERSISTENCE_SNAPSHOT_NOT_FOUND",
                        new IllegalStateException("/secret/data/payload.json")));

        mockMvc.perform(get("/api/v1/admin/persistence/snapshots/missing")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PERSISTENCE_SNAPSHOT_NOT_FOUND"))
                .andExpect(content().string(not(containsString("/secret/data/payload.json"))))
                .andExpect(content().string(not(containsString("cause"))));

        when(service.createSnapshot()).thenThrow(new PersistenceControlException(
                "PERSISTENCE_ARTIFACT_CORRUPTED", new IllegalStateException("raw payload")));
        mockMvc.perform(post("/api/v1/admin/persistence/snapshots")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PERSISTENCE_ARTIFACT_CORRUPTED"))
                .andExpect(content().string(not(containsString("raw payload"))))
                .andExpect(content().string(not(containsString("cause"))));
    }

    private PersistenceControlService.PersistenceSnapshotView snapshot() {
        return new PersistenceControlService.PersistenceSnapshotView(
                "snapshot-1", CREATED_AT, "json", 1, "b".repeat(64), "COMPLETE",
                List.of(new PersistenceControlService.PersistenceSnapshotArtifactView(
                        "governance-state", "FILE", 1, "READY", "artifacts/governance-state/data",
                        128L, "a".repeat(64), 3L)));
    }
}
