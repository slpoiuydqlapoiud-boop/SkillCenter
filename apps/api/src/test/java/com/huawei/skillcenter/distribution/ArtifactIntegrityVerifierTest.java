package com.huawei.skillcenter.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactIntegrityVerifierTest {
    @TempDir
    Path tempDir;

    @Test
    void verifiesZipAndExpectedSha256() throws Exception {
        byte[] bytes = zipBytes("SKILL.md", "# immutable");
        Path artifact = tempDir.resolve("artifact.zip");
        Files.write(artifact, bytes);

        ArtifactIntegrityVerifier.VerifiedArtifact verified = ArtifactIntegrityVerifier.verify(artifact, sha256(bytes));

        assertEquals(bytes.length, verified.sizeBytes());
        assertEquals(sha256(bytes), verified.sha256());
    }

    @Test
    void rejectsMissingArtifactInsteadOfAllowingReplacement() {
        ArtifactNotFoundException exception = assertThrows(ArtifactNotFoundException.class,
                () -> ArtifactIntegrityVerifier.verify(tempDir.resolve("missing.zip"), "a".repeat(64)));

        assertEquals("published artifact was not found", exception.getMessage());
    }

    @Test
    void rejectsHashMismatchAndNonZipContent() throws Exception {
        byte[] bytes = zipBytes("SKILL.md", "# original");
        Path artifact = tempDir.resolve("artifact.zip");
        Files.write(artifact, bytes);

        ArtifactNotFoundException mismatch = assertThrows(ArtifactNotFoundException.class,
                () -> ArtifactIntegrityVerifier.verify(artifact, "b".repeat(64)));
        assertEquals("published artifact integrity check failed", mismatch.getMessage());

        Path notZip = tempDir.resolve("not-a-zip.zip");
        Files.writeString(notZip, "not a zip");
        ArtifactNotFoundException invalidZip = assertThrows(ArtifactNotFoundException.class,
                () -> ArtifactIntegrityVerifier.verify(notZip, sha256(Files.readAllBytes(notZip))));
        assertEquals("published artifact integrity check failed", invalidZip.getMessage());
    }

    private byte[] zipBytes(String name, String content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
