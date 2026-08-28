package com.huawei.skillcenter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public class PersistenceSnapshotService {
    private static final String COMPLETE = "COMPLETE";
    private static final String READY = "READY";
    private static final String BLOCKED = "BLOCKED";

    private final PersistenceArtifactCatalog catalog;
    private final Path configuredRoot;
    private final PersistenceIntegrityService integrityService;
    private final Path snapshotRoot;
    private final PersistenceSnapshotStore snapshotStore;
    private final int manifestRetention;
    private final Clock clock;
    private final Supplier<String> snapshotIdSupplier;
    private final ObjectMapper objectMapper;
    private final PersistenceSnapshotStore.AtomicMove atomicMove;
    private final BeforeSnapshotDeletion beforeSnapshotDeletion;

    public PersistenceSnapshotService(PersistenceArtifactCatalog catalog,
                                      PersistenceControlProperties properties,
                                      Path configuredRoot,
                                      PersistenceIntegrityService integrityService) {
        this(catalog, properties, configuredRoot, integrityService, Clock.systemUTC(), defaultSnapshotIdSupplier());
    }

    PersistenceSnapshotService(PersistenceArtifactCatalog catalog,
                               PersistenceControlProperties properties,
                               Path configuredRoot,
                               PersistenceIntegrityService integrityService,
                               Clock clock,
                               Supplier<String> snapshotIdSupplier) {
        this(catalog, properties, configuredRoot, integrityService, clock, snapshotIdSupplier,
                (source, target, options) -> Files.move(source, target, options));
    }

    PersistenceSnapshotService(PersistenceArtifactCatalog catalog,
                               PersistenceControlProperties properties,
                               Path configuredRoot,
                               PersistenceIntegrityService integrityService,
                               Clock clock,
                               Supplier<String> snapshotIdSupplier,
                               PersistenceSnapshotStore.AtomicMove atomicMove) {
        this(catalog, properties, configuredRoot, integrityService, clock, snapshotIdSupplier, atomicMove,
                snapshotDir -> { });
    }

    PersistenceSnapshotService(PersistenceArtifactCatalog catalog,
                               PersistenceControlProperties properties,
                               Path configuredRoot,
                               PersistenceIntegrityService integrityService,
                               Clock clock,
                               Supplier<String> snapshotIdSupplier,
                               PersistenceSnapshotStore.AtomicMove atomicMove,
                               BeforeSnapshotDeletion beforeSnapshotDeletion) {
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (configuredRoot == null) {
            throw new IllegalArgumentException("configuredRoot must not be null");
        }
        if (integrityService == null) {
            throw new IllegalArgumentException("integrityService must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (snapshotIdSupplier == null) {
            throw new IllegalArgumentException("snapshotIdSupplier must not be null");
        }
        if (atomicMove == null) {
            throw new IllegalArgumentException("atomicMove must not be null");
        }
        if (beforeSnapshotDeletion == null) {
            throw new IllegalArgumentException("beforeSnapshotDeletion must not be null");
        }
        this.catalog = catalog;
        this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
        properties.validate(this.configuredRoot);
        this.integrityService = integrityService;
        this.snapshotRoot = properties.snapshotStoragePath(this.configuredRoot);
        this.snapshotStore = new PersistenceSnapshotStore(
                properties.controlStoragePath(this.configuredRoot).resolve("snapshots.json"),
                new ObjectMapper().findAndRegisterModules(),
                atomicMove);
        this.manifestRetention = properties.getManifestRetention();
        this.clock = clock;
        this.snapshotIdSupplier = snapshotIdSupplier;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.atomicMove = atomicMove;
        this.beforeSnapshotDeletion = beforeSnapshotDeletion;
    }

    public PersistenceSnapshotManifest createSnapshot() {
        rejectUnsupportedSnapshotBackends();
        String snapshotId = nextSnapshotId();
        Instant createdAt = clock.instant();
        Path finalSnapshotDir = validateSnapshotChild(snapshotRoot.resolve(snapshotId));
        Path temporarySnapshotDir = validateSnapshotChild(snapshotRoot.resolve(snapshotId + ".tmp"));
        ensureSnapshotRoot();
        if (Files.exists(finalSnapshotDir, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(temporarySnapshotDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }

        List<PersistenceSnapshotArtifact> artifacts = new ArrayList<>();
        try {
            Files.createDirectories(temporarySnapshotDir);
            for (PersistenceArtifactDescriptor descriptor : includedDescriptors()) {
                PersistenceArtifactStatus sourceStatus = inspectSource(descriptor);
                switch (sourceStatus.state()) {
                    case READY -> artifacts.add(copyArtifact(descriptor, sourceStatus, temporarySnapshotDir));
                    case OPTIONAL_MISSING -> artifacts.add(optionalMissingArtifact(descriptor));
                    case MISSING, CORRUPTED, MIGRATION_REQUIRED ->
                            throw new PersistenceControlException(nonBlank(sourceStatus.stableReasonCode(),
                                    "PERSISTENCE_NOT_READY"));
                }
            }
            artifacts.sort(Comparator.comparing(PersistenceSnapshotArtifact::artifactId));
            byte[] manifestBytes = buildManifestBytes(snapshotId, createdAt, artifacts, COMPLETE);
            String manifestSha256 = sha256(manifestBytes);
            Files.write(temporarySnapshotDir.resolve("manifest.json"), manifestBytes);
            Files.writeString(temporarySnapshotDir.resolve("manifest.sha256"), manifestSha256, StandardCharsets.UTF_8);
            publishSnapshotDirectory(temporarySnapshotDir, finalSnapshotDir);

            PersistenceSnapshotManifest publishedManifest = new PersistenceSnapshotManifest(
                    snapshotId,
                    createdAt,
                    "json",
                    artifacts.size(),
                    manifestSha256,
                    COMPLETE,
                    artifacts);
            persistMetadataWithRetention(publishedManifest);
            return publishedManifest;
        } catch (IOException | RuntimeException exception) {
            cleanupQuietly(temporarySnapshotDir);
            if (exception instanceof PersistenceControlException controlException) {
                throw controlException;
            }
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    public List<PersistenceSnapshotManifest> listSnapshots() {
        List<PersistenceSnapshotManifest> manifests = snapshotStore.readAll();
        validateMetadataSnapshotIds(manifests);
        return manifests;
    }

    public PersistenceSnapshotManifest getSnapshot(String snapshotId) {
        validateSnapshotId(snapshotId);
        return snapshotStore.readAll().stream()
                .filter(manifest -> manifest.snapshotId().equals(snapshotId.trim()))
                .findFirst()
                .orElseThrow(() -> new PersistenceControlException("PERSISTENCE_SNAPSHOT_NOT_FOUND"));
    }

    public RestorePreflightResult restorePreflight(String snapshotId) {
        String normalizedSnapshotId;
        try {
            normalizedSnapshotId = validateSnapshotId(snapshotId);
        } catch (PersistenceControlException exception) {
            return new RestorePreflightResult(BLOCKED, exception.getCode(), 0);
        }
        if (hasUnsupportedSnapshotBackend()) {
            return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_BACKEND_UNSUPPORTED", 0);
        }
        try {
            Path snapshotDir = validateSnapshotChild(snapshotRoot.resolve(normalizedSnapshotId));
            if (!Files.isDirectory(snapshotDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(snapshotDir)) {
                return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_NOT_FOUND", 0);
            }
            ManifestDocument manifestDocument = readManifestDocument(snapshotDir);
            List<PersistenceArtifactDescriptor> expectedDescriptors = includedDescriptors();
            if (!manifestDocument.snapshotId().equals(normalizedSnapshotId)) {
                return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
            }
            if (!"json".equals(manifestDocument.backend())
                    || !COMPLETE.equals(manifestDocument.state())
                    || manifestDocument.artifactCount() != manifestDocument.artifacts().size()) {
                return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
            }
            if (manifestDocument.artifacts().size() != expectedDescriptors.size()) {
                return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
            }
            Set<String> expectedIds = new HashSet<>();
            for (PersistenceArtifactDescriptor descriptor : expectedDescriptors) {
                expectedIds.add(descriptor.artifactId());
            }
            Set<String> actualIds = new HashSet<>();
            for (PersistenceSnapshotArtifact artifact : manifestDocument.artifacts()) {
                if (!actualIds.add(artifact.artifactId()) || !expectedIds.contains(artifact.artifactId())) {
                    return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
                }
                PersistenceArtifactDescriptor descriptor = catalog.find(artifact.artifactId())
                        .orElseThrow(() -> new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID"));
                if (!descriptor.includeInSnapshot()
                        || descriptor.kind() != artifact.kind()
                        || descriptor.schemaVersion() != artifact.schemaVersion()) {
                    return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
                }
                if (artifact.relativePath().isBlank()) {
                    if (!PersistenceSnapshotArtifact.OPTIONAL_MISSING.equals(artifact.availability())
                            || descriptor.critical()
                            || artifact.sizeBytes() != null
                            || artifact.sha256() != null
                            || artifact.recordCount() != null) {
                        return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
                    }
                    continue;
                }
                if (!PersistenceSnapshotArtifact.READY.equals(artifact.availability())
                        || artifact.sizeBytes() == null
                        || artifact.sha256() == null
                        || artifact.sha256().isBlank()
                        || artifact.recordCount() == null) {
                    return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
                }
                Path copiedPath = validateSnapshotArtifactPath(snapshotDir, artifact.relativePath());
                if (!expectedArtifactPath(artifact.artifactId()).equals(artifact.relativePath())) {
                    return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
                }
                PersistenceArtifactDescriptor copiedDescriptor = new PersistenceArtifactDescriptor(
                        descriptor.artifactId(),
                        descriptor.kind(),
                        descriptor.schemaVersion(),
                        copiedPath,
                        true,
                        true);
                PersistenceArtifactStatus copiedStatus = integrityService.inspect(copiedDescriptor);
                if (copiedStatus.state() != PersistenceArtifactState.READY
                        || !Objects.equals(copiedStatus.sizeBytes(), artifact.sizeBytes())
                        || !Objects.equals(copiedStatus.sha256(), artifact.sha256())
                        || !Objects.equals(copiedStatus.recordCount(), artifact.recordCount())) {
                    return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
                }
            }
            if (!actualIds.equals(expectedIds)) {
                return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
            }
            return new RestorePreflightResult(READY, "", manifestDocument.artifactCount());
        } catch (PersistenceControlException exception) {
            String code = exception.getCode();
            if ("PERSISTENCE_RESTORE_BLOCKED".equals(code)) {
                return new RestorePreflightResult(BLOCKED, code, 0);
            }
            return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
        } catch (IOException exception) {
            return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
        } catch (RuntimeException exception) {
            return new RestorePreflightResult(BLOCKED, "PERSISTENCE_SNAPSHOT_INVALID", 0);
        }
    }

    public record RestorePreflightResult(String status, String reasonCode, int artifactCount) {
        public RestorePreflightResult {
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("status must not be blank");
            }
            if (artifactCount < 0) {
                throw new IllegalArgumentException("artifactCount must not be negative");
            }
            status = status.trim();
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
        }
    }

    private List<PersistenceArtifactDescriptor> includedDescriptors() {
        return catalog.artifacts().stream()
                .filter(PersistenceArtifactDescriptor::includeInSnapshot)
                .sorted(Comparator.comparing(PersistenceArtifactDescriptor::artifactId))
                .toList();
    }

    private void rejectUnsupportedSnapshotBackends() {
        if (hasUnsupportedSnapshotBackend()) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_BACKEND_UNSUPPORTED");
        }
    }

    private boolean hasUnsupportedSnapshotBackend() {
        return includedDescriptors().stream()
                .anyMatch(descriptor -> !"json".equals(descriptor.physicalBackend()));
    }

    private PersistenceArtifactStatus inspectSource(PersistenceArtifactDescriptor descriptor) {
        validateBusinessPath(descriptor.storagePath());
        return integrityService.inspect(descriptor);
    }

    private PersistenceSnapshotArtifact copyArtifact(PersistenceArtifactDescriptor descriptor,
                                                     PersistenceArtifactStatus sourceStatus,
                                                     Path temporarySnapshotDir) throws IOException {
        String relativePath = "artifacts/" + descriptor.artifactId() + "/data";
        Path targetPath = validateSnapshotArtifactPath(temporarySnapshotDir, relativePath);
        copyPreservingRelativePaths(descriptor, targetPath);
        PersistenceArtifactDescriptor copiedDescriptor = new PersistenceArtifactDescriptor(
                descriptor.artifactId(),
                descriptor.kind(),
                descriptor.schemaVersion(),
                targetPath,
                descriptor.critical(),
                descriptor.includeInSnapshot());
        PersistenceArtifactStatus copiedStatus = integrityService.inspect(copiedDescriptor);
        if (copiedStatus.state() != PersistenceArtifactState.READY
                || !Objects.equals(sourceStatus.sizeBytes(), copiedStatus.sizeBytes())
                || !Objects.equals(sourceStatus.sha256(), copiedStatus.sha256())
                || !Objects.equals(sourceStatus.recordCount(), copiedStatus.recordCount())) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        return new PersistenceSnapshotArtifact(
                descriptor.artifactId(),
                descriptor.kind(),
                descriptor.schemaVersion(),
                PersistenceSnapshotArtifact.READY,
                relativePath,
                copiedStatus.sizeBytes(),
                copiedStatus.sha256(),
                copiedStatus.recordCount());
    }

    private PersistenceSnapshotArtifact optionalMissingArtifact(PersistenceArtifactDescriptor descriptor) {
        return new PersistenceSnapshotArtifact(
                descriptor.artifactId(),
                descriptor.kind(),
                descriptor.schemaVersion(),
                PersistenceSnapshotArtifact.OPTIONAL_MISSING,
                "",
                null,
                null,
                null);
    }

    private void copyPreservingRelativePaths(PersistenceArtifactDescriptor descriptor, Path targetPath) throws IOException {
        Path sourcePath = validateBusinessPath(descriptor.storagePath());
        cleanupQuietly(targetPath);
        if (descriptor.kind() == PersistenceArtifactKind.FILE) {
            if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourcePath)) {
                throw new PersistenceControlException("PERSISTENCE_ARTIFACT_CORRUPTED");
            }
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            return;
        }
        Files.createDirectories(targetPath);
        try (var stream = Files.walk(sourcePath)) {
            for (Path current : stream.toList()) {
                if (Files.isSymbolicLink(current)) {
                    throw new PersistenceControlException("PERSISTENCE_ARTIFACT_CORRUPTED");
                }
                Path relative = sourcePath.relativize(current);
                Path destination = validateSnapshotArtifactPath(targetPath, relative.toString().replace('\\', '/'));
                if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(current, destination, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                }
            }
        }
    }

    private byte[] buildManifestBytes(String snapshotId,
                                      Instant createdAt,
                                      List<PersistenceSnapshotArtifact> artifacts,
                                      String state) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("snapshotId", snapshotId);
        root.put("createdAt", createdAt.toString());
        root.put("backend", "json");
        root.put("artifactCount", artifacts.size());
        root.put("state", state);
        ArrayNode artifactsNode = root.putArray("artifacts");
        for (PersistenceSnapshotArtifact artifact : artifacts) {
            ObjectNode artifactNode = artifactsNode.addObject();
            artifactNode.put("artifactId", artifact.artifactId());
            artifactNode.put("kind", artifact.kind().name());
            artifactNode.put("schemaVersion", artifact.schemaVersion());
            artifactNode.put("availability", artifact.availability());
            artifactNode.put("relativePath", artifact.relativePath());
            if (artifact.sizeBytes() == null) {
                artifactNode.putNull("sizeBytes");
            } else {
                artifactNode.put("sizeBytes", artifact.sizeBytes());
            }
            if (artifact.sha256() == null) {
                artifactNode.putNull("sha256");
            } else {
                artifactNode.put("sha256", artifact.sha256());
            }
            if (artifact.recordCount() == null) {
                artifactNode.putNull("recordCount");
            } else {
                artifactNode.put("recordCount", artifact.recordCount());
            }
        }
        return objectMapper.writeValueAsBytes(root);
    }

    private ManifestDocument readManifestDocument(Path snapshotDir) throws IOException {
        Path manifestPath = validateSnapshotArtifactPath(snapshotDir, "manifest.json");
        Path digestPath = validateSnapshotArtifactPath(snapshotDir, "manifest.sha256");
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(digestPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(manifestPath)
                || Files.isSymbolicLink(digestPath)) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        String expectedDigest = Files.readString(digestPath, StandardCharsets.UTF_8).trim();
        if (expectedDigest.isBlank() || !expectedDigest.equals(sha256(manifestBytes))) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        JsonNode root = objectMapper.readTree(manifestBytes);
        if (root == null || !root.isObject()) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        List<PersistenceSnapshotArtifact> artifacts = new ArrayList<>();
        JsonNode artifactsNode = requiredNode(root, "artifacts");
        if (!artifactsNode.isArray()) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        for (JsonNode artifactNode : artifactsNode) {
            if (artifactNode == null || !artifactNode.isObject()) {
                throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
            }
            artifacts.add(new PersistenceSnapshotArtifact(
                    requiredText(artifactNode, "artifactId"),
                    PersistenceArtifactKind.valueOf(requiredText(artifactNode, "kind")),
                    requiredInt(artifactNode, "schemaVersion", 1),
                    requiredText(artifactNode, "availability"),
                    requiredTextAllowBlank(artifactNode, "relativePath"),
                    optionalLong(artifactNode, "sizeBytes"),
                    optionalText(artifactNode, "sha256"),
                    optionalLong(artifactNode, "recordCount")));
        }
        return new ManifestDocument(
                requiredText(root, "snapshotId"),
                Instant.parse(requiredText(root, "createdAt")),
                requiredText(root, "backend"),
                requiredInt(root, "artifactCount", 0),
                requiredText(root, "state"),
                artifacts);
    }

    private JsonNode requiredNode(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isMissingNode()) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        return node;
    }

    private String requiredText(JsonNode parent, String field) {
        JsonNode node = requiredNode(parent, field);
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        return node.textValue();
    }

    private String requiredTextAllowBlank(JsonNode parent, String field) {
        JsonNode node = requiredNode(parent, field);
        if (!node.isTextual()) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        return node.textValue();
    }

    private int requiredInt(JsonNode parent, String field, int minimum) {
        JsonNode node = requiredNode(parent, field);
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < minimum) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        return node.intValue();
    }

    private Long optionalLong(JsonNode parent, String field) {
        JsonNode node = requiredNode(parent, field);
        if (node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        return node.longValue();
    }

    private String optionalText(JsonNode parent, String field) {
        JsonNode node = requiredNode(parent, field);
        if (node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        return node.textValue();
    }

    private void publishSnapshotDirectory(Path temporarySnapshotDir, Path finalSnapshotDir) throws IOException {
        try {
            atomicMove.move(temporarySnapshotDir, finalSnapshotDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    private void persistMetadataWithRetention(PersistenceSnapshotManifest publishedManifest) {
        List<PersistenceSnapshotManifest> manifests = new ArrayList<>(snapshotStore.readAll());
        validateMetadataSnapshotIds(manifests);
        manifests.removeIf(existing -> existing.snapshotId().equals(publishedManifest.snapshotId()));
        manifests.add(publishedManifest);
        manifests.sort(Comparator.comparing(PersistenceSnapshotManifest::createdAt).reversed()
                .thenComparing(PersistenceSnapshotManifest::snapshotId, Comparator.reverseOrder()));

        int retainedCount = Math.min(Math.max(1, manifestRetention), manifests.size());
        List<PersistenceSnapshotManifest> retained = List.copyOf(manifests.subList(0, retainedCount));
        List<PersistenceSnapshotManifest> evicted = manifests.subList(retainedCount, manifests.size());
        for (PersistenceSnapshotManifest evictedManifest : evicted) {
            validatePublishedSnapshotForCleanup(evictedManifest);
        }
        snapshotStore.writeAll(retained);
        for (PersistenceSnapshotManifest evictedManifest : evicted) {
            deletePublishedSnapshot(evictedManifest.snapshotId());
        }
    }

    private void validatePublishedSnapshotForCleanup(PersistenceSnapshotManifest metadata) {
        try {
            Path snapshotDir = validateSnapshotChild(snapshotRoot.resolve(metadata.snapshotId()));
            if (!Files.isDirectory(snapshotDir, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(snapshotDir)) {
                throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
            }
            ManifestDocument manifest = readManifestDocument(snapshotDir);
            if (!metadata.snapshotId().equals(manifest.snapshotId())
                    || !"json".equals(manifest.backend())
                    || !COMPLETE.equals(manifest.state())) {
                throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof PersistenceControlException controlException) {
                throw controlException;
            }
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    private void deletePublishedSnapshot(String snapshotId) {
        Path snapshotDir = validateSnapshotChild(snapshotRoot.resolve(snapshotId));
        try {
            beforeSnapshotDeletion.run(snapshotDir);
            validateSnapshotDeletionTarget(snapshotDir);
            try (var stream = Files.walk(snapshotDir)) {
                for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(current);
                }
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof PersistenceControlException controlException) {
                throw controlException;
            }
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    private void validateSnapshotDeletionTarget(Path snapshotDir) {
        Path normalizedRoot = snapshotRoot.toAbsolutePath().normalize();
        Path normalized = snapshotDir.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)
                || !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    private Path validateBusinessPath(Path path) {
        return validateWithinRoot(path, configuredRoot, "PERSISTENCE_ARTIFACT_CORRUPTED");
    }

    private Path validateSnapshotChild(Path path) {
        return validateWithinRoot(path, snapshotRoot, "PERSISTENCE_RESTORE_BLOCKED");
    }

    private Path validateSnapshotArtifactPath(Path base, String relativePath) {
        if (relativePath == null) {
            throw new PersistenceControlException("PERSISTENCE_RESTORE_BLOCKED");
        }
        Path resolved = base.resolve(relativePath).normalize();
        return validateWithinRoot(resolved, base, "PERSISTENCE_RESTORE_BLOCKED");
    }

    private String expectedArtifactPath(String artifactId) {
        return "artifacts/" + artifactId + "/data";
    }

    private Path validateWithinRoot(Path candidate, Path root, String code) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new PersistenceControlException(code);
        }
        Path current = normalized;
        while (current != null && current.startsWith(normalizedRoot)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new PersistenceControlException(code);
            }
            if (current.equals(normalizedRoot)) {
                break;
            }
            current = current.getParent();
        }
        if (Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalizedRoot)) {
            throw new PersistenceControlException(code);
        }
        return normalized;
    }

    private String nextSnapshotId() {
        String snapshotId = validateSnapshotId(snapshotIdSupplier.get());
        return snapshotId;
    }

    private String validateSnapshotId(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        String normalized = snapshotId.trim();
        if (".".equals(normalized) || "..".equals(normalized)) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            boolean safe = Character.isLetterOrDigit(current) || current == '-' || current == '_' || current == '.';
            if (!safe) {
                throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
            }
        }
        return normalized;
    }

    private void validateMetadataSnapshotIds(List<PersistenceSnapshotManifest> manifests) {
        for (PersistenceSnapshotManifest manifest : manifests) {
            validateSnapshotId(manifest.snapshotId());
        }
    }

    private void ensureSnapshotRoot() {
        try {
            Files.createDirectories(snapshotRoot);
        } catch (IOException exception) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    private void cleanupQuietly(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                try (var stream = Files.walk(path)) {
                    for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(current);
                    }
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String nonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred.trim();
    }

    @FunctionalInterface
    interface BeforeSnapshotDeletion {
        void run(Path snapshotDir) throws IOException;
    }

    private static Supplier<String> defaultSnapshotIdSupplier() {
        return () -> "snapshot-" + System.currentTimeMillis();
    }

    private record ManifestDocument(
            String snapshotId,
            Instant createdAt,
            String backend,
            int artifactCount,
            String state,
            List<PersistenceSnapshotArtifact> artifacts) {
    }
}
