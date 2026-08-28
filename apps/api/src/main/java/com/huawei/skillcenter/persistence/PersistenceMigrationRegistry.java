package com.huawei.skillcenter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

public class PersistenceMigrationRegistry {
    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target, boolean replaceExisting) throws IOException;
    }

    private final Path configuredRoot;
    private final PersistenceIntegrityService integrityService;
    private final List<PersistenceMigration> migrations;
    private final PersistenceMigrationJournal journal;
    private final Clock clock;
    private final Supplier<String> requestIdSupplier;
    private final MoveOperation moveOperation;

    public PersistenceMigrationRegistry(PersistenceControlProperties properties,
                                        Path configuredRoot,
                                        PersistenceIntegrityService integrityService,
                                        List<PersistenceMigration> migrations,
                                        Clock clock,
                                        Supplier<String> requestIdSupplier) {
        this(properties, configuredRoot, integrityService, migrations, clock, requestIdSupplier,
                (source, target, replaceExisting) -> {
                    try {
                        if (replaceExisting) {
                            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        } else {
                            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                        }
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        if (replaceExisting) {
                            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.move(source, target);
                        }
                    }
                });
    }

    PersistenceMigrationRegistry(PersistenceControlProperties properties,
                                 Path configuredRoot,
                                 PersistenceIntegrityService integrityService,
                                 List<PersistenceMigration> migrations,
                                 Clock clock,
                                 Supplier<String> requestIdSupplier,
                                 MoveOperation moveOperation) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (configuredRoot == null) {
            throw new IllegalArgumentException("configuredRoot must not be null");
        }
        if (integrityService == null) {
            throw new IllegalArgumentException("integrityService must not be null");
        }
        if (migrations == null) {
            throw new IllegalArgumentException("migrations must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (requestIdSupplier == null) {
            throw new IllegalArgumentException("requestIdSupplier must not be null");
        }
        if (moveOperation == null) {
            throw new IllegalArgumentException("moveOperation must not be null");
        }
        this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
        properties.validate(this.configuredRoot);
        this.integrityService = integrityService;
        this.migrations = List.copyOf(migrations);
        this.journal = new PersistenceMigrationJournal(
                properties.controlStoragePath(this.configuredRoot).resolve("migration-journal.json"),
                new ObjectMapper().findAndRegisterModules());
        this.clock = clock;
        this.requestIdSupplier = requestIdSupplier;
        this.moveOperation = moveOperation;
    }

    public List<PersistenceArtifactStatus> ensureCurrent(List<PersistenceArtifactDescriptor> descriptors) {
        if (descriptors == null) {
            throw new IllegalArgumentException("descriptors must not be null");
        }
        List<PersistenceArtifactStatus> statuses = new ArrayList<>();
        for (PersistenceArtifactDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                throw new IllegalArgumentException("descriptor must not be null");
            }
            statuses.add(ensureCurrent(descriptor));
        }
        return List.copyOf(statuses);
    }

    private PersistenceArtifactStatus ensureCurrent(PersistenceArtifactDescriptor descriptor) {
        validateWithinRoot(descriptor.storagePath());
        PersistenceArtifactStatus status = integrityService.inspect(descriptor);
        if (status.state() == PersistenceArtifactState.CORRUPTED) {
            throw new PersistenceControlException("PERSISTENCE_ARTIFACT_CORRUPTED");
        }
        if (status.state() == PersistenceArtifactState.MISSING
                || status.state() == PersistenceArtifactState.OPTIONAL_MISSING) {
            return status;
        }
        int currentVersion = journal.currentVersion(descriptor.artifactId()).orElse(1);
        if (currentVersion > descriptor.schemaVersion()) {
            throw new PersistenceControlException("PERSISTENCE_MIGRATION_UNSUPPORTED");
        }
        if (currentVersion == descriptor.schemaVersion()) {
            return readyStatus(descriptor, status, currentVersion);
        }
        int version = currentVersion;
        while (version < descriptor.schemaVersion()) {
            PersistenceMigration migration = resolveMigration(descriptor.artifactId(), version)
                    .orElseThrow(() -> new PersistenceControlException("PERSISTENCE_MIGRATION_UNSUPPORTED"));
            applyMigration(descriptor, migration);
            version = migration.toVersion();
        }
        return readyStatus(descriptor, integrityService.inspect(descriptor), version);
    }

    private PersistenceArtifactStatus readyStatus(PersistenceArtifactDescriptor descriptor,
                                                  PersistenceArtifactStatus status,
                                                  int observedVersion) {
        return new PersistenceArtifactStatus(
                status.artifactId(),
                status.state(),
                descriptor.schemaVersion(),
                observedVersion,
                status.sizeBytes(),
                status.sha256(),
                status.recordCount(),
                status.checkedAt(),
                status.stableReasonCode());
    }

    private Optional<PersistenceMigration> resolveMigration(String artifactId, int fromVersion) {
        return migrations.stream()
                .filter(migration -> migration.artifactId().equals(artifactId))
                .filter(migration -> migration.fromVersion() == fromVersion)
                .filter(migration -> migration.toVersion() > fromVersion)
                .min(Comparator.comparingInt(PersistenceMigration::toVersion));
    }

    private void applyMigration(PersistenceArtifactDescriptor descriptor, PersistenceMigration migration) {
        Path artifactPath = validateWithinRoot(descriptor.storagePath());
        Path temporaryPath = siblingTempPath(artifactPath);
        try {
            prepareTemporaryCopy(descriptor.kind(), artifactPath, temporaryPath);
            migration.apply(temporaryPath);
            replaceAtomically(temporaryPath, artifactPath);
            journal.appendSuccess(
                    descriptor.artifactId(),
                    migration.fromVersion(),
                    migration.toVersion(),
                    clock.instant(),
                    safeRequestId());
        } catch (IOException | RuntimeException exception) {
            cleanupQuietly(temporaryPath);
            throw new PersistenceControlException("PERSISTENCE_MIGRATION_FAILED");
        }
    }

    private Path validateWithinRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(configuredRoot)) {
            throw new PersistenceControlException("PERSISTENCE_ARTIFACT_CORRUPTED");
        }
        Path current = normalized;
        while (current != null && current.startsWith(configuredRoot)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new PersistenceControlException("PERSISTENCE_ARTIFACT_CORRUPTED");
            }
            if (current.equals(configuredRoot)) {
                break;
            }
            current = current.getParent();
        }
        Path controlRoot = journal.path().getParent();
        if (controlRoot != null) {
            Path controlCurrent = controlRoot;
            while (controlCurrent != null && controlCurrent.startsWith(configuredRoot)) {
                if (Files.exists(controlCurrent, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(controlCurrent)) {
                    throw new PersistenceControlException("PERSISTENCE_ARTIFACT_CORRUPTED");
                }
                if (controlCurrent.equals(configuredRoot)) {
                    break;
                }
                controlCurrent = controlCurrent.getParent();
            }
        }
        return normalized;
    }

    private Path siblingTempPath(Path artifactPath) {
        return artifactPath.resolveSibling(artifactPath.getFileName() + ".migration-tmp");
    }

    private void prepareTemporaryCopy(PersistenceArtifactKind kind, Path source, Path target) throws IOException {
        cleanupQuietly(target);
        if (kind == PersistenceArtifactKind.FILE) {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            return;
        }
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path current : stream.toList()) {
                if (Files.isSymbolicLink(current)) {
                    throw new PersistenceControlException("PERSISTENCE_ARTIFACT_CORRUPTED");
                }
                Path relative = source.relativize(current);
                Path destination = target.resolve(relative.toString());
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

    private void replaceAtomically(Path source, Path target) throws IOException {
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            replaceDirectory(source, target);
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void replaceDirectory(Path source, Path target) throws IOException {
        Path backup = target.resolveSibling(target.getFileName() + ".migration-backup");
        cleanupQuietly(backup);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            move(source, target, false);
            return;
        }
        move(target, backup, false);
        boolean restored = false;
        try {
            move(source, target, false);
            cleanupQuietly(backup);
        } catch (IOException replacementFailure) {
            cleanupQuietly(target);
            try {
                move(backup, target, false);
                restored = true;
            } catch (IOException restoreFailure) {
                replacementFailure.addSuppressed(restoreFailure);
            }
            if (!restored) {
                cleanupQuietly(backup);
            }
            throw replacementFailure;
        }
    }

    private void move(Path source, Path target, boolean replaceExisting) throws IOException {
        moveOperation.move(source, target, replaceExisting);
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

    private String safeRequestId() {
        String requestId = requestIdSupplier.get();
        return requestId == null ? "" : requestId.trim();
    }
}
