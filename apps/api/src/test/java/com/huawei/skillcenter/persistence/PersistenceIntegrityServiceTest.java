package com.huawei.skillcenter.persistence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceIntegrityServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void inspectsJsonFileAndReportsStableDigestSizeAndRootCount() throws Exception {
        Path artifactPath = tempDir.resolve("data/governance/state.json");
        Files.createDirectories(artifactPath.getParent());
        String payload = "{\"alpha\":1,\"beta\":[1,2,3]}";
        Files.writeString(artifactPath, payload, StandardCharsets.UTF_8);

        PersistenceArtifactStatus status = service().inspect(descriptor(
                "governance-state",
                PersistenceArtifactKind.FILE,
                1,
                artifactPath,
                true));

        assertThat(status.artifactId()).isEqualTo("governance-state");
        assertThat(status.state()).isEqualTo(PersistenceArtifactState.READY);
        assertThat(status.schemaVersion()).isEqualTo(1);
        assertThat(status.observedVersion()).isEqualTo(1);
        assertThat(status.sizeBytes()).isEqualTo((long) payload.getBytes(StandardCharsets.UTF_8).length);
        assertThat(status.sha256()).isEqualTo(sha256(payload.getBytes(StandardCharsets.UTF_8)));
        assertThat(status.recordCount()).isEqualTo(2L);
        assertThat(status.stableReasonCode()).isEmpty();
        assertThat(status.checkedAt()).isNotNull();
    }

    @Test
    void inspectsDirectoryAndComputesDeterministicDigest() throws Exception {
        Path artifactPath = tempDir.resolve("data/packages");
        Path nested = artifactPath.resolve("nested/a.txt");
        Path rootFile = artifactPath.resolve("b.txt");
        Files.createDirectories(nested.getParent());
        Files.writeString(nested, "alpha", StandardCharsets.UTF_8);
        Files.writeString(rootFile, "bravo", StandardCharsets.UTF_8);

        PersistenceArtifactStatus status = service().inspect(descriptor(
                "packages",
                PersistenceArtifactKind.DIRECTORY,
                1,
                artifactPath,
                false));

        assertThat(status.state()).isEqualTo(PersistenceArtifactState.READY);
        assertThat(status.observedVersion()).isEqualTo(1);
        assertThat(status.sizeBytes()).isEqualTo(10L);
        assertThat(status.recordCount()).isEqualTo(2L);
        assertThat(status.sha256()).isEqualTo(directoryDigest(artifactPath, List.of(
                artifactPath.relativize(rootFile),
                artifactPath.relativize(nested))));
        assertThat(status.stableReasonCode()).isEmpty();
    }

    @Test
    void reportsOptionalMissingForNonCriticalArtifact() {
        Path artifactPath = tempDir.resolve("data/governance/optional.json");

        PersistenceArtifactStatus status = service().inspect(descriptor(
                "optional-artifact",
                PersistenceArtifactKind.FILE,
                1,
                artifactPath,
                false));

        assertThat(status.state()).isEqualTo(PersistenceArtifactState.OPTIONAL_MISSING);
        assertThat(status.stableReasonCode()).isEqualTo("PERSISTENCE_ARTIFACT_MISSING");
        assertThat(status.sizeBytes()).isNull();
        assertThat(status.sha256()).isNull();
        assertThat(status.recordCount()).isNull();
    }

    @Test
    void reportsCorruptedForMalformedJsonWithoutLeakingPayload() throws Exception {
        Path artifactPath = tempDir.resolve("data/governance/state.json");
        Files.createDirectories(artifactPath.getParent());
        String payload = "{\"secret\":\"top-secret\"";
        Files.writeString(artifactPath, payload, StandardCharsets.UTF_8);

        PersistenceArtifactStatus status = service().inspect(descriptor(
                "governance-state",
                PersistenceArtifactKind.FILE,
                1,
                artifactPath,
                true));

        assertThat(status.state()).isEqualTo(PersistenceArtifactState.CORRUPTED);
        assertThat(status.stableReasonCode()).isEqualTo("PERSISTENCE_ARTIFACT_CORRUPTED");
        assertThat(status.sizeBytes()).isEqualTo((long) payload.getBytes(StandardCharsets.UTF_8).length);
        assertThat(status.sha256()).isEqualTo(sha256(payload.getBytes(StandardCharsets.UTF_8)));
        assertThat(status.recordCount()).isNull();
        assertThat(status.stableReasonCode()).doesNotContain("top-secret");
    }

    @Test
    void reportsCorruptedWhenArtifactPathContainsSymlinkAncestor() throws Exception {
        Path realRoot = tempDir.resolve("real-root");
        Path linkedRoot = createSymlinkOrSkip(tempDir.resolve("linked-root"), realRoot);
        Path realArtifactPath = realRoot.resolve("data/governance/state.json");
        Files.createDirectories(realArtifactPath.getParent());
        Files.writeString(realArtifactPath, "{\"alpha\":1}", StandardCharsets.UTF_8);

        PersistenceArtifactStatus status = service().inspect(descriptor(
                "governance-state",
                PersistenceArtifactKind.FILE,
                1,
                linkedRoot.resolve("data/governance/state.json"),
                true));

        assertThat(status.state()).isEqualTo(PersistenceArtifactState.CORRUPTED);
        assertThat(status.stableReasonCode()).isEqualTo("PERSISTENCE_ARTIFACT_CORRUPTED");
        assertThat(status.sizeBytes()).isNull();
        assertThat(status.sha256()).isNull();
        assertThat(status.recordCount()).isNull();
    }

    @Test
    void reportsCorruptedWhenDirectoryContainsSymlinkSubdirectory() throws Exception {
        Path artifactPath = tempDir.resolve("data/packages");
        Path sharedDir = tempDir.resolve("shared");
        Files.createDirectories(artifactPath);
        Files.createDirectories(sharedDir);
        Files.writeString(sharedDir.resolve("secret.txt"), "alpha", StandardCharsets.UTF_8);
        createSymlinkOrSkip(artifactPath.resolve("linked-dir"), sharedDir);

        PersistenceArtifactStatus status = service().inspect(descriptor(
                "packages",
                PersistenceArtifactKind.DIRECTORY,
                1,
                artifactPath,
                false));

        assertThat(status.state()).isEqualTo(PersistenceArtifactState.CORRUPTED);
        assertThat(status.stableReasonCode()).isEqualTo("PERSISTENCE_ARTIFACT_CORRUPTED");
        assertThat(status.sizeBytes()).isNull();
        assertThat(status.sha256()).isNull();
        assertThat(status.recordCount()).isNull();
    }

    private PersistenceIntegrityService service() {
        return new PersistenceIntegrityService(tempDir);
    }

    private PersistenceArtifactDescriptor descriptor(String artifactId,
                                                    PersistenceArtifactKind kind,
                                                    int schemaVersion,
                                                    Path storagePath,
                                                    boolean critical) {
        return new PersistenceArtifactDescriptor(artifactId, kind, schemaVersion, storagePath, critical, true);
    }

    private String directoryDigest(Path root, List<Path> paths) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path relativePath : paths.stream().sorted().toList()) {
            byte[] bytes = Files.readAllBytes(root.resolve(relativePath));
            String line = relativePath.toString().replace('\\', '/')
                    + "\t"
                    + bytes.length
                    + "\t"
                    + sha256(bytes)
                    + "\n";
            digest.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Path createSymlinkOrSkip(Path link, Path target) throws Exception {
        try {
            Path parent = link.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + exception.getClass().getSimpleName());
        } catch (Exception exception) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }
        throw new IllegalStateException("symbolic link assumption should have aborted test");
    }
}
