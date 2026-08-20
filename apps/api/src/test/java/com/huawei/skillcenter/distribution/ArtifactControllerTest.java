package com.huawei.skillcenter.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ArtifactControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validAuthorizationDownloadsThePublishedZipAndConsumesToken() throws Exception {
        String token = issueForUploadedSkill();

        mockMvc.perform(get("/api/v1/distribution/artifacts/summarize-release-notes/1.0.0")
                        .queryParam("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("X-Skill-SHA256", matchesPattern("[0-9a-f]{64}")));

        mockMvc.perform(get("/api/v1/distribution/artifacts/summarize-release-notes/1.0.0")
                        .queryParam("token", token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("DISTRIBUTION_AUTHORIZATION_INVALID"));
    }

    @Test
    void seededSkillAuthorizationDownloadsGeneratedZipArtifact() throws Exception {
        MvcResult installation = mockMvc.perform(post("/api/v1/skills/eox-query/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"manual-zip\"}")
                        .header("X-User-Id", "seed-download-user")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(installation.getResponse().getContentAsString())
                .path("data").path("authorization").path("token").asText();

        mockMvc.perform(get("/api/v1/distribution/artifacts/eox-query/1.2.0")
                        .queryParam("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Length", matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string("X-Skill-SHA256", matchesPattern("[0-9a-f]{64}")));
    }

    @Test
    void downloadSelectsPublishedVersionWhenPendingVersionsShareTheSameSkillVersion() throws Exception {
        MvcResult installation = mockMvc.perform(post("/api/v1/skills/skill-only/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"manual-zip\"}")
                        .header("X-User-Id", "duplicate-version-download-user")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(installation.getResponse().getContentAsString())
                .path("data").path("authorization").path("token").asText();

        mockMvc.perform(get("/api/v1/distribution/artifacts/skill-only/1.0.0")
                        .queryParam("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"));
    }

    @Test
    void malformedAuthorizationIsReportedAsStableClientError() throws Exception {
                mockMvc.perform(get("/api/v1/distribution/artifacts/summarize-release-notes/1.0.0")
                        .queryParam("token", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DISTRIBUTION_AUTHORIZATION_INVALID"));
    }

    @Test
    void authorizationCanBeConsumedThroughTheExplicitExchangeEndpoint() throws Exception {
        String token = issueForUploadedSkill();
        MvcResult installation = mockMvc.perform(post("/api/v1/skills/summarize-release-notes/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"cli\"}")
                        .header("X-User-Id", "alice-exchange")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode authorization = objectMapper.readTree(installation.getResponse().getContentAsString())
                .path("data").path("authorization");

        mockMvc.perform(post("/api/v1/distribution/authorizations/{tokenId}/consume", authorization.path("tokenId").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + authorization.path("token").asText() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenId").value(authorization.path("tokenId").asText()))
                .andExpect(jsonPath("$.data.consumedAt").isNotEmpty());
    }

    private String issueForUploadedSkill() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/v1/skill-packages")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "summarize-release-notes-1.0.0.zip", "application/zip",
                                new FileSystemResource(canonicalExample()).getInputStream()))
                        .header("X-User-Id", "maintainer-artifact")
                        .header("X-User-Role", "maintainer"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode uploadData = objectMapper.readTree(upload.getResponse().getContentAsString()).path("data");
        String packageId = uploadData.path("packageId").asText();
        MvcResult reviews = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/reviews")
                        .header("X-User-Id", "reviewer-artifact")
                        .header("X-User-Role", "reviewer"))
                .andExpect(status().isOk())
                .andReturn();
        String reviewId = null;
        for (JsonNode review : objectMapper.readTree(reviews.getResponse().getContentAsString()).path("data")) {
            if (packageId.equals(review.path("packageId").asText())) {
                reviewId = review.path("reviewId").asText();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(reviewId);
        mockMvc.perform(post("/api/v1/admin/reviews/{reviewId}/approve", reviewId)
                        .header("X-User-Id", "reviewer-artifact")
                        .header("X-User-Role", "reviewer"))
                .andExpect(status().isOk());

        MvcResult installation = mockMvc.perform(post("/api/v1/skills/summarize-release-notes/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"manual-zip\"}")
                        .header("X-User-Id", "alice-artifact")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(installation.getResponse().getContentAsString())
                .path("data").path("authorization").path("token").asText();
    }

    private static String canonicalExample() {
        java.nio.file.Path workingDirectory = java.nio.file.Path.of(System.getProperty("user.dir"));
        java.nio.file.Path repositoryRoot = java.nio.file.Files.isDirectory(workingDirectory.resolve("examples"))
                ? workingDirectory
                : workingDirectory.getParent().getParent();
        return repositoryRoot.resolve("examples/packages/summarize-release-notes-1.0.0.zip").toString();
    }
}
