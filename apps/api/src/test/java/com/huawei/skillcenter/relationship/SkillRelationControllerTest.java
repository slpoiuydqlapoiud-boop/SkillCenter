package com.huawei.skillcenter.relationship;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillRelationControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private SkillRelationService service;
    private ActorResolver actorResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(SkillRelationService.class);
        actorResolver = mock(ActorResolver.class);
        when(actorResolver.resolve(any())).thenReturn(new Actor("admin", "admin"));
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillRelationController(service, actorResolver)).build();
    }

    @Test
    void createsRelationWithSafeResponseAndRequestIdEnvelope() throws Exception {
        SkillRelation relation = relation();
        when(service.create(any(SkillRelationRequest.class), any(Actor.class), any(String.class))).thenReturn(relation);

        mockMvc.perform(post("/api/v1/admin/skill-relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SkillRelationRequest(
                                "skill-a", "1.0.0", "skill-b", "2.0.0", SkillRelationType.DEPENDS_ON))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.relationId").value("relation-1"))
                .andExpect(jsonPath("$.data.relationType").value("DEPENDS_ON"))
                .andExpect(jsonPath("$.data.declaredBy").doesNotExist())
                .andExpect(jsonPath("$.requestId").value("null"));
    }

    @Test
    void listsRelationsWithFiltersAndDoesNotExposeSensitiveFields() throws Exception {
        when(service.list(any(SkillRelationQuery.class), any(Actor.class))).thenReturn(List.of(relation()));

        mockMvc.perform(get("/api/v1/admin/skill-relations")
                        .param("sourceSkillId", "skill-a")
                        .param("sourceVersion", "1.0.0")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].relationId").value("relation-1"))
                .andExpect(jsonPath("$.data", not(hasItem("prompt"))))
                .andExpect(jsonPath("$.data[0].declaredBy").doesNotExist());
    }

    @Test
    void returnsBoundedImpactAndRetiresRelation() throws Exception {
        when(service.impact(eq("skill-b"), eq("2.0.0"), any(SkillRelationQuery.class), any(Actor.class)))
                .thenReturn(new SkillRelationImpact("skill-b", "2.0.0", 5, 100, false,
                        List.of(new SkillRelationImpactNode("relation-1", "skill-a", "1.0.0",
                                SkillRelationType.DEPENDS_ON, 1, "published", true, 2))));
        when(service.retire(eq("relation-1"), eq("migration complete"), any(Actor.class), any(String.class)))
                .thenReturn(relation().retire("admin", "migration complete", Instant.parse("2026-08-24T02:00:00Z")));

        mockMvc.perform(get("/api/v1/admin/skill-relations/impact")
                        .param("skillId", "skill-b").param("version", "2.0.0").param("maxDepth", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].skillId").value("skill-a"))
                .andExpect(jsonPath("$.data.nodes[0].productionPromoted").value(true))
                .andExpect(jsonPath("$.data.nodes[0].activeInstallationCount").value(2));

        mockMvc.perform(post("/api/v1/admin/skill-relations/relation-1/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"migration complete\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETIRED"));
    }

    private SkillRelation relation() {
        return SkillRelation.create("relation-1", "skill-a", "1.0.0", "skill-b", "2.0.0",
                SkillRelationType.DEPENDS_ON, "admin", Instant.parse("2026-08-24T01:00:00Z"));
    }
}
