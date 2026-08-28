package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrganizationDirectoryControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanReadLocalDirectoryStatusWithoutMembershipProjection() throws Exception {
        mockMvc.perform(get("/api/v1/admin/governance/organization-directory")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOCAL"))
                .andExpect(jsonPath("$.data.memberUserIds").doesNotExist())
                .andExpect(jsonPath("$.data.credentialRef").doesNotExist());
    }

    @Test
    void adminCanTriggerLocalSyncWithoutNetworkCall() throws Exception {
        mockMvc.perform(post("/api/v1/admin/governance/organization-directory/sync")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOCAL"));
    }

    @Test
    void nonAdminCannotReadOrSyncDirectoryStatus() throws Exception {
        mockMvc.perform(get("/api/v1/admin/governance/organization-directory")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/admin/governance/organization-directory/sync")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
