package com.huawei.skillcenter.personal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PersonalCenterControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void personalCenterEndpointsUseCurrentActorScope() throws Exception {
        mockMvc.perform(get("/api/v1/me/installations")
                        .header("X-User-Id", "personal-test-user")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/api/v1/me/skills")
                        .header("X-User-Id", "personal-test-user")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/api/v1/me/favorites")
                        .header("X-User-Id", "personal-test-user")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/api/v1/me/invocations?page=1&pageSize=10")
                        .header("X-User-Id", "personal-test-user")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }
}
