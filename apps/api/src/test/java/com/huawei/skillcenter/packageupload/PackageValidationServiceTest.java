package com.huawei.skillcenter.packageupload;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        assertThat(result.securityStatus()).isEqualTo("PASSED");
        assertThat(result.securityFindings()).isEmpty();
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
        assertThat(result.riskLevel()).isEqualTo("low");
    }

    @Test
    void derivesHighRiskFromDeclaredWriteExternalAndCredentialPermissions() throws IOException {
        Path zip = fixtureZip(Map.of(
                "high-risk-skill/SKILL.md", "---\nname: high-risk-skill\ndescription: A high risk skill\n---\n",
                "high-risk-skill/skill.json", """
                        {"schemaVersion":"1.0","id":"high-risk-skill","name":"High Risk Skill","version":"1.0.0",
                         "description":"A high risk skill for governance tests","ownerTeam":"security-team","maintainers":["u100001"],
                         "category":"operations","tags":[],"compatibility":[{"client":"codex","minimumVersion":"1.0.0"}],
                         "permissions":{"workspace":"write","network":"external","shell":"restricted","credentials":"declared","mcpServers":[]},
                         "dependencies":[],"language":["en"],"entry":"SKILL.md"}
                        """));

        PackageValidationResult result = service.validate(zip);

        assertThat(result.valid()).isTrue();
        assertThat(result.riskLevel()).isEqualTo("high");
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

    @Test
    void blocksExecutablePayloadAndReportsStableSecurityFinding() throws IOException {
        Path zip = fixtureZip(Map.of(
                "safe-skill/SKILL.md", "---\nname: safe-skill\ndescription: safe\n---\n",
                "safe-skill/payload.exe", "MZ executable payload"));

        PackageValidationResult result = service.validate(zip);

        assertThat(result.valid()).isFalse();
        assertThat(result.securityStatus()).isEqualTo("BLOCKED");
        assertThat(result.securityFindings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("EXECUTABLE_PAYLOAD");
            assertThat(finding.path()).isEqualTo("safe-skill/payload.exe");
            assertThat(finding.severity()).isEqualTo("HIGH");
        });
        assertThat(result.errors()).anyMatch(error -> error.code().equals("EXECUTABLE_PAYLOAD"));
    }

    @Test
    void blocksCredentialPatternWithoutEchoingSecretValue() throws IOException {
        String secret = "sk-" + "A".repeat(32);
        Path zip = fixtureZip(Map.of(
                "safe-skill/SKILL.md", "---\nname: safe-skill\ndescription: safe\n---\n\nToken: " + secret));

        PackageValidationResult result = service.validate(zip);

        assertThat(result.valid()).isFalse();
        assertThat(result.securityStatus()).isEqualTo("BLOCKED");
        assertThat(result.securityFindings()).anySatisfy(finding ->
                assertThat(finding.code()).isEqualTo("SECRET_PATTERN"));
        assertThat(result.errors().toString()).doesNotContain(secret);
        assertThat(result.securityFindings().toString()).doesNotContain(secret);
    }

    @Test
    void requiredExternalScannerProvenanceFlowsThroughValidation() {
        ExternalPackageSecurityScanner external = new ExternalPackageSecurityScanner() {
            @Override
            public String scannerId() {
                return "approved-engine";
            }

            @Override
            public String scannerVersion() {
                return "2026.1";
            }

            @Override
            public ExternalPackageSecurityScannerHealth health() {
                return new ExternalPackageSecurityScannerHealth("READY", "TEST_READY");
            }

            @Override
            public Set<PackageSecurityScanCapability> capabilities() {
                return Set.of(PackageSecurityScanCapability.values());
            }

            @Override
            public PackageSecurityScanResult scan(Path zipPath) {
                return new PackageSecurityScanResult("PASSED", scannerId(), scannerVersion(), List.of());
            }
        };
        PackageValidationService requiredService = new PackageValidationService(
                new ObjectMapper(), 20 * 1024 * 1024, 100 * 1024 * 1024,
                new PackageSecurityScanCoordinator(new PackageSecurityScanService(), external,
                        PackageSecurityExternalMode.REQUIRED));

        PackageValidationResult result = requiredService.validate(canonicalExample());

        assertThat(result.valid()).isTrue();
        assertThat(result.securityStatus()).isEqualTo("PASSED");
        assertThat(result.securityScannerId()).isEqualTo("approved-engine");
        assertThat(result.securityScannerVersion()).isEqualTo("2026.1");
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
