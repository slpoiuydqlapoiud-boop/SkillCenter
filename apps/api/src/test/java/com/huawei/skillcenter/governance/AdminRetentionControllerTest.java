package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminRetentionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void reviewerCanReadButCannotUpdateRetentionPolicy() throws Exception {
        mockMvc.perform(get("/api/v1/admin/retention")
                        .header("X-User-Role", "reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auditRetentionDays").value(365));

        mockMvc.perform(put("/api/v1/admin/retention")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyVersion\":1,\"auditRetentionDays\":365,\"invocationRetentionDays\":90,\"installationRetentionDays\":90}")
                        .header("X-User-Role", "reviewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
