package com.huawei.skillcenter.notification;

import com.huawei.skillcenter.governance.GovernanceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "skill-center.governance-storage=target/test-notifications-state.json")
class NotificationControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GovernanceStore store;

    @Test
    void exposesListAndReadActions() throws Exception {
        store.addNotification(new NotificationRecord("n-1", "developer-1", "system", "测试通知",
                "详情", "bell", Instant.now(), false, null));
        mockMvc.perform(get("/api/v1/me/notifications").header("X-User-Id", "developer-1")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.unreadCount").isNumber());

        mockMvc.perform(put("/api/v1/me/notifications/n-1/read")
                        .header("X-User-Id", "developer-1").header("X-User-Role", "developer"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/me/notifications/read-all")
                        .header("X-User-Id", "developer-1").header("X-User-Role", "developer"))
                .andExpect(status().isOk());
    }
}
