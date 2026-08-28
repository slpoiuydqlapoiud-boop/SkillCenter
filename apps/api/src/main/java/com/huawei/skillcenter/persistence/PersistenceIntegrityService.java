package com.huawei.skillcenter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

public class PersistenceIntegrityService {
    private static final String MISSING_CODE = "PERSISTENCE_ARTIFACT_MISSING";
    private static final String CORRUPTED_CODE = "PERSISTENCE_ARTIFACT_CORRUPTED";

    private final Path configuredRoot;
    private final ObjectMapper objectMapper;

    public PersistenceIntegrityService(Path configuredRoot) {
        this(configuredRoot, new ObjectMapper().findAndRegisterModules());
    }

    PersistenceIntegrityService(Path configuredRoot, ObjectMapper objectMapper) {
        if (configuredRoot == null) {
            throw new IllegalArgumentException("configuredRoot must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public PersistenceArtifactStatus inspect(PersistenceArtifactDescriptor descriptor) {
        Instant checkedAt = Instant.now();
        final Path artifactPath;
        try {
            artifactPath = validateWithinRoot(descriptor.storagePath());
        } catch (PersistenceControlException exception) {
            return corruptedStatus(descriptor, null, null, null, checkedAt);
        }
        if (!Files.exists(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
            return missingStatus(descriptor, checkedAt);
        }
        try {
            return switch (descriptor.kind()) {
                case FILE -> inspectFile(descriptor, artifactPath, checkedAt);
                case DIRECTORY -> inspectDirectory(descriptor, artifactPath, checkedAt);
            };
        } catch (IOException | RuntimeException exception) {
            return corruptedStatus(descriptor, null, null, null, checkedAt);
        }
    }

    private PersistenceArtifactStatus inspectFile(PersistenceArtifactDescriptor descriptor,
                                                  Path artifactPath,
                                                  Instant checkedAt) throws IOException {
        if (!Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(artifactPath)) {
            return corruptedStatus(descriptor, null, null, null, checkedAt);
        }
        byte[] bytes = Files.readAllBytes(artifactPath);
        Long sizeBytes = (long) bytes.length;
        String sha256 = sha256(bytes);
        try {
            JsonNode root = objectMapper.readTree(bytes);
            return new PersistenceArtifactStatus(
                    descriptor.artifactId(),
                    PersistenceArtifactState.READY,
                    descriptor.schemaVersion(),
                    descriptor.schemaVersion(),
                    sizeBytes,
                    sha256,
                    rootCount(root),
                    checkedAt,
                    "");
        } catch (IOException malformedJson) {
            return corruptedStatus(descriptor, sizeBytes, sha256, null, checkedAt);
        }
    }

    private PersistenceArtifactStatus inspectDirectory(PersistenceArtifactDescriptor descriptor,
                                                       Path artifactPath,
                                                       Instant checkedAt) throws IOException {
        if (!Files.isDirectory(artifactPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(artifactPath)) {
            return corruptedStatus(descriptor, null, null, null, checkedAt);
        }
        MessageDigest digest = newDigest();
        long totalBytes = 0L;
        long fileCount = 0L;
        try (Stream<Path> stream = Files.walk(artifactPath)) {
            List<Path> entries = stream
                    .filter(path -> !path.equals(artifactPath))
                    .sorted(Comparator.comparing(path -> artifactPath.relativize(path).toString().replace('\\', '/')))
                    .toList();
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    return corruptedStatus(descriptor, null, null, null, checkedAt);
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Path file = entry;
                Path relativePath = artifactPath.relativize(file);
                byte[] bytes = Files.readAllBytes(file);
                totalBytes += bytes.length;
                fileCount++;
                String line = relativePath.toString().replace('\\', '/')
                        + "\t"
                        + bytes.length
                        + "\t"
                        + sha256(bytes)
                        + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        return new PersistenceArtifactStatus(
                descriptor.artifactId(),
                PersistenceArtifactState.READY,
                descriptor.schemaVersion(),
                descriptor.schemaVersion(),
                totalBytes,
                HexFormat.of().formatHex(digest.digest()),
                fileCount,
                checkedAt,
                "");
    }

    private PersistenceArtifactStatus missingStatus(PersistenceArtifactDescriptor descriptor, Instant checkedAt) {
        return new PersistenceArtifactStatus(
                descriptor.artifactId(),
                descriptor.critical() ? PersistenceArtifactState.MISSING : PersistenceArtifactState.OPTIONAL_MISSING,
                descriptor.schemaVersion(),
                null,
                null,
                null,
                null,
                checkedAt,
                MISSING_CODE);
    }

    private PersistenceArtifactStatus corruptedStatus(PersistenceArtifactDescriptor descriptor,
                                                      Long sizeBytes,
                                                      String sha256,
                                                      Long recordCount,
                                                      Instant checkedAt) {
        return new PersistenceArtifactStatus(
                descriptor.artifactId(),
                PersistenceArtifactState.CORRUPTED,
                descriptor.schemaVersion(),
                null,
                sizeBytes,
                sha256,
                recordCount,
                checkedAt,
                CORRUPTED_CODE);
    }

    private Path validateWithinRoot(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(configuredRoot)) {
            throw new PersistenceControlException(CORRUPTED_CODE);
        }
        Path current = normalized;
        while (current != null && current.startsWith(configuredRoot)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new PersistenceControlException(CORRUPTED_CODE);
            }
            if (current.equals(configuredRoot)) {
                break;
            }
            current = current.getParent();
        }
        if (Files.exists(configuredRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(configuredRoot)) {
            throw new PersistenceControlException(CORRUPTED_CODE);
        }
        return normalized;
    }

    private long rootCount(JsonNode root) {
        if (root == null || root.isNull()) {
            return 0L;
        }
        if (root.isArray()) {
            return root.size();
        }
        if (root.isObject()) {
            return root.size();
        }
        return 1L;
    }

    private String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
