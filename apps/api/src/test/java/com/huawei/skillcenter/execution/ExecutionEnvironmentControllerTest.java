package com.huawei.skillcenter.execution;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ExecutionEnvironmentControllerTest {
    private static final String STATE_PATH = Path.of("target", "execution-environments-controller-" + System.nanoTime() + ".json")
            .toAbsolutePath().toString();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("skill-center.execution-environment-storage", () -> STATE_PATH);
    }

    @Test
    void adminCanListCreateAndDisableEnvironmentWithoutSecretLeak() throws Exception {
        mockMvc.perform(get("/api/v1/admin/execution-environments")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].environmentId").value("openclaw"))
                .andExpect(jsonPath("$.data[0].configReference").value(""))
                .andExpect(jsonPath("$.data[0].endpoint").doesNotExist());

        mockMvc.perform(post("/api/v1/admin/execution-environments")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environmentId\":\"team-runtime\",\"kind\":\"AGENT_RUNTIME\",\"version\":\"runtime-v2\",\"capabilities\":[\"execute\"],\"adapterProviderId\":\"openclaw-runner\",\"configReference\":\"secret://team/runtime\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.environmentId").value("team-runtime"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.configReference").value("secret://team/runtime"));

        mockMvc.perform(patch("/api/v1/admin/execution-environments/AGENT_RUNTIME/team-runtime/status")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    @Test
    void nonAdminCannotManageAndDuplicateRegistrationIsConflict() throws Exception {
        mockMvc.perform(get("/api/v1/admin/execution-environments")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/admin/execution-environments")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environmentId\":\"openclaw\",\"kind\":\"AGENT_RUNTIME\",\"version\":\"context-v1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXECUTION_ENVIRONMENT_CONFLICT"));
    }
}
