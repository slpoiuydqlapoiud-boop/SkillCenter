package com.huawei.skillcenter.packageupload;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PackageValidationServiceTest {
    @TempDir
    Path tempDir;

    private final PackageValidationService service = new PackageValidationService(new ObjectMapper(), 20 * 1024 * 1024, 100 * 1024 * 1024);

    @Test
    void acceptsCanonicalExamplePackage() {
        PackageValidationResult result = service.validate(canonicalExample());

        assertThat(result.valid()).isTrue();
        assertThat(result.skillId()).isEqualTo("summarize-release-notes");
        assertThat(result.version()).isEqualTo("1.0.0");
        assertThat(result.sha256()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void acceptsPackageWithoutSkillJsonAndDerivesIdentityFromRoot() throws IOException {
        Path zip = fixtureZip(Map.of(
                "skill-only/SKILL.md", "---\nname: skill-only\ndescription: A skill without metadata\n---\n\n# Skill\n"));

        PackageValidationResult result = service.validate(zip);

        assertThat(result.valid()).isTrue();
        assertThat(result.skillId()).isEqualTo("skill-only");
        assertThat(result.version()).isEqualTo("1.0.0");
        assertThat(result.errors()).isEmpty();
    }

    private Path canonicalExample() {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path repositoryRoot = Files.isDirectory(workingDirectory.resolve("examples"))
                ? workingDirectory
                : workingDirectory.getParent().getParent();
        return repositoryRoot.resolve("examples/packages/summarize-release-notes-1.0.0.zip");
    }

    @Test
    void rejectsPathTraversalEntry() throws IOException {
        Path zip = fixtureZip(Map.of(
                "safe-skill/SKILL.md", "---\nname: safe-skill\ndescription: safe\n---\n",
                "safe-skill/skill.json", "{\"schemaVersion\":\"1.0\",\"id\":\"safe-skill\",\"name\":\"Safe\",\"version\":\"1.0.0\"}",
                "../escape.txt", "blocked"));

        assertThat(service.validate(zip).errors()).anyMatch(error -> error.code().equals("UNSAFE_PATH"));
    }

    @Test
    void rejectsNestedZipEntry() throws IOException {
        Path zip = fixtureZip(Map.of(
                "safe-skill/SKILL.md", "---\nname: safe-skill\ndescription: safe\n---\n",
                "safe-skill/skill.json", "{\"schemaVersion\":\"1.0\",\"id\":\"safe-skill\",\"name\":\"Safe\",\"version\":\"1.0.0\"}",
                "safe-skill/nested.zip", "not allowed"));

        assertThat(service.validate(zip).errors()).anyMatch(error -> error.code().equals("NESTED_ARCHIVE"));
    }

    private Path fixtureZip(Map<String, String> entries) throws IOException {
        Path zip = tempDir.resolve("fixture.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return zip;
    }
}
