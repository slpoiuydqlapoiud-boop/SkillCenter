package com.huawei.skillcenter.packageupload;

import com.huawei.skillcenter.governance.GovernanceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PackageControllerTest {
    private static final java.util.concurrent.atomic.AtomicLong NEXT_TEST_VERSION = new java.util.concurrent.atomic.AtomicLong();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GovernanceStore governanceStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void uploadCreatesPendingReviewForCanonicalExample() throws Exception {
        String version = nextTestVersion();
        mockMvc.perform(multipart("/api/v1/skill-packages")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file",
                                "summarize-release-notes-" + version + ".zip",
                                "application/zip",
                                new FileSystemResource(versionedExample(version)).getInputStream())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("pending_review"))
                .andExpect(jsonPath("$.data.skillId").value("summarize-release-notes"))
                .andExpect(jsonPath("$.data.securityStatus").value("PASSED"))
                .andExpect(jsonPath("$.data.securityFindings").isEmpty());

        assertThat(governanceStore.snapshot().versions().stream()
                .filter(candidate -> version.equals(candidate.version()))
                .findFirst()
                .orElseThrow()
                .artifactPath())
                .startsWith("local://sha256/")
                .doesNotContain(java.nio.file.Path.of(System.getProperty("user.dir")).toString());
    }

    @Test
    void uploadAcceptsPackageWithoutSkillJson() throws Exception {
        String skillId = "skill-only-controller-" + System.currentTimeMillis();
        mockMvc.perform(multipart("/api/v1/skill-packages")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file",
                                "skill-only.zip",
                                "application/zip",
                                skillMdOnlyZip(skillId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.skillId").value(skillId))
                .andExpect(jsonPath("$.data.version").value("1.0.0"));
    }

    @Test
    void resumableUploadCanPauseAndResumeBeforeReviewSubmission() throws Exception {
        String version = nextTestVersion();
        byte[] packageBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(versionedExample(version)));
        int split = Math.max(1, packageBytes.length / 2);
        String createResponse = mockMvc.perform(post("/api/v1/skill-packages/uploads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(new CreateUploadRequest(
                                "summarize-release-notes-" + version + ".zip", packageBytes.length))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.receivedBytes").value(0))
                .andReturn().getResponse().getContentAsString();
        String uploadId = objectMapper.readTree(createResponse).path("data").path("uploadId").asText();

        mockMvc.perform(put("/api/v1/skill-packages/uploads/{uploadId}", uploadId)
                        .contentType("application/octet-stream")
                        .header("Content-Range", "bytes 0-" + (split - 1) + "/" + packageBytes.length)
                        .content(java.util.Arrays.copyOfRange(packageBytes, 0, split)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedBytes").value(split));

        mockMvc.perform(get("/api/v1/skill-packages/uploads/{uploadId}", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("uploading"));

        mockMvc.perform(put("/api/v1/skill-packages/uploads/{uploadId}", uploadId)
                        .contentType("application/octet-stream")
                        .header("Content-Range", "bytes " + split + "-" + (packageBytes.length - 1) + "/" + packageBytes.length)
                        .content(java.util.Arrays.copyOfRange(packageBytes, split, packageBytes.length)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ready"));

        mockMvc.perform(post("/api/v1/skill-packages/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("pending_review"))
                .andExpect(jsonPath("$.data.skillId").value("summarize-release-notes"));
    }

    @Test
    void resumableUploadCanBeCancelledAndNoLongerExposesSession() throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/skill-packages/uploads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(new CreateUploadRequest("cancel.zip", 3))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String uploadId = objectMapper.readTree(createResponse).path("data").path("uploadId").asText();

        mockMvc.perform(delete("/api/v1/skill-packages/uploads/{uploadId}", uploadId))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/skill-packages/uploads/{uploadId}", uploadId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_NOT_FOUND"));
    }

    private record CreateUploadRequest(String fileName, long totalBytes) {
    }

    private static byte[] skillMdOnlyZip(String skillId) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(skillId + "/SKILL.md"));
            output.write("---\nname: skill-only\ndescription: A skill without metadata\n---\n\n# Skill\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String canonicalExample() {
        java.nio.file.Path workingDirectory = java.nio.file.Path.of(System.getProperty("user.dir"));
        java.nio.file.Path repositoryRoot = java.nio.file.Files.isDirectory(workingDirectory.resolve("examples"))
                ? workingDirectory
                : workingDirectory.getParent().getParent();
        return repositoryRoot.resolve("examples/packages/summarize-release-notes-1.0.0.zip").toString();
    }

    private static String nextTestVersion() {
        return "4000.0." + System.nanoTime() + NEXT_TEST_VERSION.incrementAndGet();
    }

    private static String versionedExample(String version) throws java.io.IOException {
        java.nio.file.Path source = java.nio.file.Path.of(canonicalExample());
        java.nio.file.Path target = java.nio.file.Files.createTempFile("summarize-release-notes-" + version + "-", ".zip");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source.toFile());
             java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(target))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if (entry.getName().equals("summarize-release-notes/skill.json")) {
                    String json = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)
                            .replace("\"version\": \"1.0.0\"", "\"version\": \"" + version + "\"");
                    output.write(json.getBytes(StandardCharsets.UTF_8));
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
