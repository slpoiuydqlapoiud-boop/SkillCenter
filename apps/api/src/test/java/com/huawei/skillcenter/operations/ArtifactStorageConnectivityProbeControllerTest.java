package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ArtifactStorageConnectivityProbeControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanProbeArtifactStorageWithoutExposingLocalPath() throws Exception {
        mockMvc.perform(post("/api/v1/admin/platform/artifact-storage/probe")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backend").value("local"))
                .andExpect(jsonPath("$.data.status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.reasonCode").value("ARTIFACT_STORAGE_LOCAL_ONLY"))
                .andExpect(jsonPath("$.data.endpoint").doesNotExist())
                .andExpect(jsonPath("$.data.path").doesNotExist());
    }

    @Test
    void nonAdminCannotProbeArtifactStorage() throws Exception {
        mockMvc.perform(post("/api/v1/admin/platform/artifact-storage/probe")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
