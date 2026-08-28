package com.huawei.skillcenter.packageupload;

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
class ResumableUploadReadinessControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanInspectLocalOnlyUploadReadiness() throws Exception {
        mockMvc.perform(get("/api/v1/admin/platform/resumable-uploads/readiness")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backend").value("local"))
                .andExpect(jsonPath("$.data.status").value("LOCAL_ONLY"));
    }

    @Test
    void nonAdminCannotInspectUploadReadiness() throws Exception {
        mockMvc.perform(get("/api/v1/admin/platform/resumable-uploads/readiness")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
