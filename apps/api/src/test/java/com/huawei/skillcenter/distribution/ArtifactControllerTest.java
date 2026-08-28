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
    private static final java.util.concurrent.atomic.AtomicLong NEXT_TEST_VERSION = new java.util.concurrent.atomic.AtomicLong();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validAuthorizationDownloadsThePublishedZipAndConsumesToken() throws Exception {
        String version = nextTestVersion();
        String token = issueForUploadedSkill(version);

        mockMvc.perform(get("/api/v1/distribution/artifacts/summarize-release-notes/{version}", version)
                        .queryParam("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("X-Skill-SHA256", matchesPattern("[0-9a-f]{64}")));

        mockMvc.perform(get("/api/v1/distribution/artifacts/summarize-release-notes/{version}", version)
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
        String skillId = "skill-only-" + System.nanoTime();
        publishSkillOnlyFixture(skillId);
        MvcResult installation = mockMvc.perform(post("/api/v1/skills/{skillId}/installations", skillId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"manual-zip\"}")
                        .header("X-User-Id", "duplicate-version-download-user")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(installation.getResponse().getContentAsString())
                .path("data").path("authorization").path("token").asText();

        mockMvc.perform(get("/api/v1/distribution/artifacts/{skillId}/1.0.0", skillId)
                        .queryParam("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"));
    }

    private void publishSkillOnlyFixture(String skillId) throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/v1/skill-packages")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", skillId + ".zip", "application/zip", skillMdOnlyZip(skillId)))
                        .header("X-User-Id", "admin-artifact")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isCreated())
                .andReturn();
        String packageId = objectMapper.readTree(upload.getResponse().getContentAsString())
                .path("data").path("packageId").asText();
        MvcResult reviews = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/admin/reviews").header("X-User-Id", "reviewer-artifact")
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
    }

    private static byte[] skillMdOnlyZip(String skillId) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(bytes)) {
            output.putNextEntry(new java.util.zip.ZipEntry(skillId + "/SKILL.md"));
            output.write(("---\nname: skill-only\ndescription: Artifact fixture\n---\n\n# Skill\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
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
        String version = nextTestVersion();
        String token = issueForUploadedSkill(version);
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

    @Test
    void unauthorizedHeadersAreRejectedBeforeTokenConsumptionOnDownload() throws Exception {
        String version = nextTestVersion();
        String token = issueForUploadedSkill(version);
        restrictSkillToMaintainer("summarize-release-notes", "allowed-download-user");

        mockMvc.perform(get("/api/v1/distribution/artifacts/summarize-release-notes/{version}", version)
                        .queryParam("token", token)
                        .header("X-User-Id", "denied-download-user")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SKILL_NOT_VISIBLE"));

        mockMvc.perform(get("/api/v1/distribution/artifacts/summarize-release-notes/{version}", version)
                        .queryParam("token", token)
                        .header("X-User-Id", "allowed-download-user")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("X-Skill-SHA256", matchesPattern("[0-9a-f]{64}")));
    }

    private String issueForUploadedSkill(String version) throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/v1/skill-packages")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "summarize-release-notes-" + version + ".zip", "application/zip",
                                new FileSystemResource(versionedExample(version)).getInputStream()))
                        .header("X-User-Id", "admin-artifact")
                        .header("X-User-Role", "admin"))
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
        updateScope("summarize-release-notes", "PUBLIC", java.util.List.of());

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

    private void restrictSkillToMaintainer(String skillId, String allowedUserId) throws Exception {
        updateScope(skillId, "RESTRICTED", java.util.List.of(allowedUserId));
    }

    private void updateScope(String skillId, String visibility, java.util.List<String> maintainers) throws Exception {
        int revision = currentScopeRevision(skillId);
        String body = "{\"visibility\":\"" + visibility + "\",\"ownerTeamId\":\"\",\"maintainerUserIds\":"
                + objectMapper.writeValueAsString(maintainers) + ",\"revision\":" + revision + "}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/skill-access/scopes/{skillId}", skillId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-User-Id", "scope-admin")
                        .header("X-User-Role", "admin"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.data.visibility").value(visibility));
    }

    private int currentScopeRevision(String skillId) throws Exception {
        MvcResult current = mockMvc.perform(get("/api/v1/admin/skill-access/scopes")
                        .queryParam("skillId", skillId)
                        .header("X-User-Id", "scope-admin")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(current.getResponse().getContentAsString()).path("data").path("revision").asInt();
    }

    private static String nextTestVersion() {
        return "4000.0." + System.nanoTime() + NEXT_TEST_VERSION.incrementAndGet();
    }

    private static String versionedExample(String version) throws java.io.IOException {
        java.nio.file.Path workingDirectory = java.nio.file.Path.of(System.getProperty("user.dir"));
        java.nio.file.Path repositoryRoot = java.nio.file.Files.isDirectory(workingDirectory.resolve("examples"))
                ? workingDirectory
                : workingDirectory.getParent().getParent();
        java.nio.file.Path source = repositoryRoot.resolve("examples/packages/summarize-release-notes-1.0.0.zip");
        java.nio.file.Path target = java.nio.file.Files.createTempFile("summarize-release-notes-" + version + "-", ".zip");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source.toFile());
             java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(target))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if (entry.getName().equals("summarize-release-notes/skill.json")) {
                    String json = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .replaceFirst("\"version\"\\s*:\\s*\"[^\"]+\"", "\"version\": \"" + version + "\"");
                    output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else if (!entry.isDirectory()) {
                    zip.getInputStream(entry).transferTo(output);
                }
                output.closeEntry();
            }
        }
        target.toFile().deleteOnExit();
        return target.toString();
    }
}
