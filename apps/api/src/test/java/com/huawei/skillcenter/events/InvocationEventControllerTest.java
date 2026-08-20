package com.huawei.skillcenter.events;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InvocationEventControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsUnknownContentFields() throws Exception {
        mockMvc.perform(post("/api/v1/events/invocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schemaVersion":"1.0",
                                  "eventId":"2d8f1d8f-68a2-4c50-a4d5-ec5f6d1f9fd2",
                                  "occurredAt":"2026-08-17T15:00:00+08:00",
                                  "skillId":"eox-query",
                                  "version":"1.2.0",
                                  "subject":{"userId":"user-1","teamId":"network-team"},
                                  "client":{"type":"codex","version":"1.0.0"},
                                  "sessionId":"session_1234567890",
                                  "status":"success",
                                  "durationMs":42,
                                  "prompt":"must-not-be-accepted"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVENT_SCHEMA_INVALID"));
    }

    @Test
    void acceptsInvocationBatchWithPerEventResults() throws Exception {
        mockMvc.perform(post("/api/v1/events/invocations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "batchId":"batch-1",
                                  "schemaVersion":"1.0",
                                  "events":[
                                    {
                                      "schemaVersion":"1.0",
                                      "eventId":"2d8f1d8f-68a2-4c50-a4d5-ec5f6d1f9fd2",
                                      "occurredAt":"2026-08-17T15:00:00+08:00",
                                      "skillId":"eox-query",
                                      "version":"1.2.0",
                                      "subject":{"userId":"user-1","teamId":"network-team"},
                                      "client":{"type":"codex","version":"1.0.0"},
                                      "sessionId":"session_1234567890",
                                      "status":"success",
                                      "durationMs":42
                                    },
                                    {
                                      "schemaVersion":"1.0",
                                      "eventId":"3d8f1d8f-68a2-4c50-a4d5-ec5f6d1f9fd2",
                                      "occurredAt":"2026-08-17T15:01:00+08:00",
                                      "skillId":"eox-query",
                                      "version":"1.2.0",
                                      "subject":{"userId":"user-1","teamId":"network-team"},
                                      "client":{"type":"codex","version":"1.0.0"},
                                      "sessionId":"session_1234567890",
                                      "status":"failure",
                                      "durationMs":42
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.rejected").value(1))
                .andExpect(jsonPath("$.data.results[1].errorCode").value("EVENT_SCHEMA_INVALID"));
    }
}
