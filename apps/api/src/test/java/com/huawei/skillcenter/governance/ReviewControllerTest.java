package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        String skillId = "review-skill-" + System.currentTimeMillis();
        MvcResult upload = mockMvc.perform(multipart("/api/v1/skill-packages")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", skillId + ".zip", "application/zip", skillMdOnlyZip(skillId)))
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

    private static byte[] skillMdOnlyZip(String skillId) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(skillId + "/SKILL.md"));
            output.write(("---\nname: " + skillId + "\ndescription: Review test skill\n---\n\n# Review\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
