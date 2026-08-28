package com.huawei.skillcenter.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

class PersistenceSnapshotStore {
    private static final TypeReference<List<PersistenceSnapshotManifest>> SNAPSHOT_LIST =
            new TypeReference<>() { };

    private final Path metadataPath;
    private final ObjectMapper objectMapper;
    private final AtomicMove atomicMove;

    PersistenceSnapshotStore(Path metadataPath, ObjectMapper objectMapper) {
        this(metadataPath, objectMapper, (source, target, options) -> Files.move(source, target, options));
    }

    PersistenceSnapshotStore(Path metadataPath, ObjectMapper objectMapper, AtomicMove atomicMove) {
        if (metadataPath == null) {
            throw new IllegalArgumentException("metadataPath must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        if (atomicMove == null) {
            throw new IllegalArgumentException("atomicMove must not be null");
        }
        this.metadataPath = metadataPath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.atomicMove = atomicMove;
    }

    List<PersistenceSnapshotManifest> readAll() {
        validateStorageLayout();
        if (!Files.exists(metadataPath, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (Files.isSymbolicLink(metadataPath)) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        try {
            List<PersistenceSnapshotManifest> manifests = objectMapper.readValue(metadataPath.toFile(), SNAPSHOT_LIST);
            return manifests.stream()
                    .sorted(Comparator.comparing(PersistenceSnapshotManifest::createdAt).reversed()
                            .thenComparing(PersistenceSnapshotManifest::snapshotId, Comparator.reverseOrder()))
                    .toList();
        } catch (IOException | RuntimeException exception) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    void writeAll(List<PersistenceSnapshotManifest> manifests) {
        if (manifests == null) {
            throw new IllegalArgumentException("manifests must not be null");
        }
        Path temporaryPath = null;
        try {
            Path parent = metadataPath.getParent();
            if (parent != null) {
                validateParentLayout(parent);
                Files.createDirectories(parent);
                validateParentLayout(parent);
            }
            if (Files.isSymbolicLink(metadataPath)) {
                throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
            }
            Path fixedTemporaryPath = metadataPath.resolveSibling(metadataPath.getFileName() + ".tmp");
            if (Files.isSymbolicLink(fixedTemporaryPath)) {
                throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
            }
            if (parent == null) {
                throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
            }
            temporaryPath = Files.createTempFile(parent, metadataPath.getFileName().toString() + ".", ".tmp");
            if (Files.isSymbolicLink(temporaryPath)
                    || !Files.isRegularFile(temporaryPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
            }
            try (OutputStream output = Files.newOutputStream(temporaryPath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                objectMapper.writeValue(output, manifests);
            }
            atomicMove.move(temporaryPath, metadataPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (PersistenceControlException exception) {
            deleteQuietly(temporaryPath);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(temporaryPath);
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    Path path() {
        return metadataPath;
    }

    private void validateStorageLayout() {
        Path parent = metadataPath.getParent();
        if (parent == null) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
        validateParentLayout(parent);
        if (Files.isSymbolicLink(metadataPath)) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    private void validateParentLayout(Path parent) {
        validateNoSymlinkAncestors(parent);
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent))) {
            throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    private void validateNoSymlinkAncestors(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new PersistenceControlException("PERSISTENCE_SNAPSHOT_INVALID");
            }
            current = current.getParent();
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    @FunctionalInterface
    interface AtomicMove {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }
}
