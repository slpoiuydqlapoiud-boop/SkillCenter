package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReviewControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void reviewerCanApprovePendingUpload() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/v1/skill-packages")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "summarize-release-notes-1.0.0.zip", "application/zip",
                                new FileSystemResource(canonicalExample()).getInputStream()))
                        .header("X-User-Id", "maintainer-1")
                        .header("X-User-Role", "maintainer"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("pending_review"))
                .andReturn();

        JsonNode uploadData = objectMapper.readTree(upload.getResponse().getContentAsString()).path("data");
        String packageId = uploadData.path("packageId").asText();
        MvcResult reviews = mockMvc.perform(get("/api/v1/admin/reviews")
                        .header("X-User-Id", "reviewer-1")
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
                        .header("X-User-Id", "reviewer-1")
                        .header("X-User-Role", "reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("approved"));
    }

    @Test
    void viewerCannotReadReviewQueue() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reviews")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private static String canonicalExample() {
        java.nio.file.Path workingDirectory = java.nio.file.Path.of(System.getProperty("user.dir"));
        java.nio.file.Path repositoryRoot = java.nio.file.Files.isDirectory(workingDirectory.resolve("examples"))
                ? workingDirectory
                : workingDirectory.getParent().getParent();
        return repositoryRoot.resolve("examples/packages/summarize-release-notes-1.0.0.zip").toString();
    }
}
