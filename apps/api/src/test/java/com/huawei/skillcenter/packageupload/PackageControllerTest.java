package com.huawei.skillcenter.packageupload;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PackageControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadCreatesPendingReviewForCanonicalExample() throws Exception {
        mockMvc.perform(multipart("/api/v1/skill-packages")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file",
                                "summarize-release-notes-1.0.0.zip",
                                "application/zip",
                                new FileSystemResource(canonicalExample()).getInputStream())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("pending_review"))
                .andExpect(jsonPath("$.data.skillId").value("summarize-release-notes"));
    }

    @Test
    void uploadAcceptsPackageWithoutSkillJson() throws Exception {
        mockMvc.perform(multipart("/api/v1/skill-packages")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file",
                                "skill-only.zip",
                                "application/zip",
                                skillMdOnlyZip())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.skillId").value("skill-only"))
                .andExpect(jsonPath("$.data.version").value("1.0.0"));
    }

    private static byte[] skillMdOnlyZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry("skill-only/SKILL.md"));
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
}
