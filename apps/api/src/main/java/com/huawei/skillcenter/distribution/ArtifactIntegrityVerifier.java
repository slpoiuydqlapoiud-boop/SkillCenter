package com.huawei.skillcenter.distribution;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Verifies that an immutable published artifact still matches its recorded identity. */
public final class ArtifactIntegrityVerifier {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private ArtifactIntegrityVerifier() {
    }

    public static VerifiedArtifact verify(Path artifactPath, String expectedSha256) {
        if (artifactPath == null || !Files.isRegularFile(artifactPath) || Files.isSymbolicLink(artifactPath)
                || !artifactPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new ArtifactNotFoundException("published artifact was not found");
        }
        if (expectedSha256 == null || !SHA256.matcher(expectedSha256.trim()).matches()) {
            throw new ArtifactNotFoundException("published artifact integrity check failed");
        }
        try {
            try (ZipFile ignored = new ZipFile(artifactPath.toFile())) {
                // Opening the archive verifies that the stored bytes are a readable ZIP.
            }
            long sizeBytes = Files.size(artifactPath);
            String actualSha256 = sha256(artifactPath);
            if (!actualSha256.equalsIgnoreCase(expectedSha256.trim())) {
                throw new ArtifactNotFoundException("published artifact integrity check failed");
            }
            return new VerifiedArtifact(actualSha256, sizeBytes);
        } catch (ArtifactNotFoundException exception) {
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ArtifactNotFoundException("published artifact integrity check failed");
        }
    }

    private static String sha256(Path artifactPath) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(artifactPath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public record VerifiedArtifact(String sha256, long sizeBytes) {
        public VerifiedArtifact {
            if (sha256 == null || !SHA256.matcher(sha256).matches() || sizeBytes < 0) {
                throw new IllegalArgumentException("invalid verified artifact metadata");
            }
            sha256 = sha256.toLowerCase(Locale.ROOT);
        }
    }
}
