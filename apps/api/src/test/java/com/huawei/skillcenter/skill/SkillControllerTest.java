package com.huawei.skillcenter.skill;

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
class SkillControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void listFiltersByCategoryAndReturnsPageMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/skills").param("category", "网络运维"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].category").value("网络运维"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(12));
    }

    @Test
    void missingSkillReturnsNotFoundCode() throws Exception {
        mockMvc.perform(get("/api/v1/skills/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SKILL_NOT_FOUND"));
    }

    @Test
    void contentEndpointReturnsAnEmptyDocumentForRepositorySkills() throws Exception {
        mockMvc.perform(get("/api/v1/skills/eox-query/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(""));
    }

    @Test
    void listSupportsServerSideSortAndPageMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/skills")
                        .param("sort", "calls")
                        .param("page", "1")
                        .param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("knowledge-qa"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(5));
    }
}
