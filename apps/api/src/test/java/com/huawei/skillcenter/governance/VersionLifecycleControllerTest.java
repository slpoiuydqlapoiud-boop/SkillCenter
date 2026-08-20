package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VersionLifecycleControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingVersionReturnsStableNotFoundContract() throws Exception {
        mockMvc.perform(get("/api/v1/skills/missing/versions/1.0.0/impact")
                        .header("X-User-Id", "reviewer-1")
                        .header("X-User-Role", "reviewer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SKILL_VERSION_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void nonAdminCannotChangeLifecycleState() throws Exception {
        mockMvc.perform(post("/api/v1/skills/eox-query/versions/1.0.0/deprecate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test\"}")
                        .header("X-User-Id", "reviewer-1")
                        .header("X-User-Role", "reviewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
