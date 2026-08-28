package com.huawei.skillcenter.search;

import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillSearchRefreshConsumerControllerTest {
    private MockMvc mockMvc;
    private SkillSearchRefreshEventStore store;

    @BeforeEach
    void setUp() {
        store = mock(SkillSearchRefreshEventStore.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillSearchRefreshConsumerController(store, new ActorResolver()))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();
    }

    @Test
    void consumerControlIsAdminOnlyAndMetadataOnly() throws Exception {
        SkillSearchRefreshConsumerState state = new SkillSearchRefreshConsumerState("api-1", 12L,
                SkillSearchRefreshConsumerStatus.ACTIVE, Instant.parse("2026-08-28T12:00:00Z"), null);
        when(store.listConsumers()).thenReturn(List.of(state));

        mockMvc.perform(get("/api/v1/admin/search/consumers")
                        .header(ActorResolver.USER_ID_HEADER, "viewer-1")
                        .header(ActorResolver.ROLE_HEADER, "viewer"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/search/consumers")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].consumerId").value("api-1"))
                .andExpect(jsonPath("$.data[0].lastEventSeq").value(12))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].sourceHash").doesNotExist())
                .andExpect(jsonPath("$.data[0].credentials").doesNotExist());
    }

    @Test
    void adminCanRetireAndReactivateAConsumer() throws Exception {
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        SkillSearchRefreshConsumerState retired = new SkillSearchRefreshConsumerState("api-1", 12L,
                SkillSearchRefreshConsumerStatus.RETIRED, now, now);
        SkillSearchRefreshConsumerState active = new SkillSearchRefreshConsumerState("api-1", 12L,
                SkillSearchRefreshConsumerStatus.ACTIVE, now, null);
        when(store.retireConsumer(org.mockito.ArgumentMatchers.eq("api-1"),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(retired);
        when(store.activateConsumer(org.mockito.ArgumentMatchers.eq("api-1"),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(active);

        mockMvc.perform(post("/api/v1/admin/search/consumers/api-1/retire")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETIRED"))
                .andExpect(jsonPath("$.requestId").exists());

        mockMvc.perform(post("/api/v1/admin/search/consumers/api-1/activate")
                        .header(ActorResolver.USER_ID_HEADER, "admin-1")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }
}
