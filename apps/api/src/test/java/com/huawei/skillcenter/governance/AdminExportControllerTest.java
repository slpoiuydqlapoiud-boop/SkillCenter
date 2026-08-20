package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminExportControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanCreateAndInspectExportTask() throws Exception {
        String body = "{\"dataset\":\"AUDIT_SUMMARY\",\"format\":\"JSON\",\"filters\":{}}";

        mockMvc.perform(post("/api/v1/admin/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-User-Id", "export-admin")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").isNotEmpty())
                .andExpect(jsonPath("$.data.status", anyOf(is("QUEUED"), is("RUNNING"), is("COMPLETED"))))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void viewerCannotCreateExport() throws Exception {
        mockMvc.perform(post("/api/v1/admin/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataset\":\"AUDIT_SUMMARY\",\"format\":\"JSON\",\"filters\":{}}")
                        .header("X-User-Id", "viewer")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EXPORT_FORBIDDEN"));
    }

    @Test
    void unknownExportReturnsSafeNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/admin/exports/00000000-0000-0000-0000-000000000000")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EXPORT_NOT_FOUND"));
    }

    @Test
    void repeatedExportKeyIsReplayAndDifferentPayloadConflicts() throws Exception {
        String key = "export-idempotency-1";
        String first = "{\"dataset\":\"AUDIT_SUMMARY\",\"format\":\"JSON\",\"filters\":{}}";
        String different = "{\"dataset\":\"INVOCATION_SUMMARY\",\"format\":\"JSON\",\"filters\":{}}";

        mockMvc.perform(post("/api/v1/admin/exports")
                        .header("Idempotency-Key", key)
                        .header("X-User-Id", "idempotent-export-admin")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/admin/exports")
                        .header("Idempotency-Key", key)
                        .header("X-User-Id", "idempotent-export-admin")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        mockMvc.perform(post("/api/v1/admin/exports")
                        .header("Idempotency-Key", key)
                        .header("X-User-Id", "idempotent-export-admin")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }
}
