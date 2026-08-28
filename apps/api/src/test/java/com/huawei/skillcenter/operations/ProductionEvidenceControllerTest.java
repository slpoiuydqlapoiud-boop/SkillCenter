package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductionEvidenceControllerTest {
    private MockMvc mockMvc;
    private ActorResolver actorResolver;

    @BeforeEach
    void setUp() {
        actorResolver = mock(ActorResolver.class);
        ProductionEvidenceService service = mock(ProductionEvidenceService.class);
        when(actorResolver.resolve(any())).thenReturn(new Actor("admin-user", "admin"));
        when(service.list(any())).thenReturn(List.of(new ProductionEvidence(
                "DATABASE_CAPACITY_SLO", "ACCEPTED", "admin", Instant.parse("2026-08-25T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), "change-1", "validated", 1, "admin",
                Instant.parse("2026-08-25T00:00:00Z"))));
        when(service.update(any(), any(), any(), any())).thenReturn(new ProductionEvidence(
                "DATABASE_CAPACITY_SLO", "ACCEPTED", "admin", Instant.parse("2026-08-25T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), "change-1", "validated", 1, "admin",
                Instant.parse("2026-08-25T00:00:00Z")));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductionEvidenceController(service, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();
    }

    @Test
    void adminCanReadAndUpdateSafeEvidenceMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/admin/platform/evidence")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req-evidence-get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-evidence-get"))
                .andExpect(jsonPath("$.data[0].evidenceId").value("DATABASE_CAPACITY_SLO"))
                .andExpect(content().string(not(containsString("password"))));

        mockMvc.perform(put("/api/v1/admin/platform/evidence/DATABASE_CAPACITY_SLO")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req-evidence-put")
                        .contentType("application/json")
                        .content("""
                                {"status":"ACCEPTED","ownerUserId":"owner","expiresAt":"2026-09-01T00:00:00Z","evidenceRef":"change-1","summary":"validated","expectedRevision":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
    }
}
