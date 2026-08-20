package com.huawei.skillcenter.governance;

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
class VersionHistoryControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicVersionHistoryContainsOnlyPublishedMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/skills/eox-query/versions").header("X-User-Role", "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].status").value("published"))
                .andExpect(jsonPath("$.data[0].artifactPath").doesNotExist());
    }
}
