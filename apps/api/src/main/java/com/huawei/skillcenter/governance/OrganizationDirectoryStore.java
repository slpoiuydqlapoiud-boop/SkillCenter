package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Atomic local persistence for the last organization directory control-plane state. */
public class OrganizationDirectoryStore {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private OrganizationDirectoryState current;

    public OrganizationDirectoryStore(Path statePath, ObjectMapper objectMapper, Clock clock) {
        if (statePath == null || objectMapper == null) throw new IllegalArgumentException("directory store is invalid");
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.current = readState();
    }

    public OrganizationDirectoryState state() {
        lock.readLock().lock();
        try {
            return current;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<OrganizationDirectorySnapshot> activeSnapshot(Instant now, long maxAgeSeconds) {
        lock.readLock().lock();
        try {
            return current.isActiveAt(now, maxAgeSeconds) ? current.snapshot() : Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    public OrganizationDirectoryState accept(OrganizationDirectorySnapshot snapshot, Instant attemptedAt) {
        if (snapshot == null || attemptedAt == null) throw new IllegalArgumentException("directory snapshot is invalid");
        if (snapshot.fetchedAt().isAfter(attemptedAt)) {
            throw new IllegalArgumentException("directory snapshot is from the future");
        }
        lock.writeLock().lock();
        try {
            if (current.snapshot().isPresent()) {
                OrganizationDirectorySnapshot previous = current.snapshot().get();
                if (previous.revision().equals(snapshot.revision())
                        && previous.contentHash().equals(snapshot.contentHash())) {
                    current = OrganizationDirectoryState.active(previous, attemptedAt);
                    persist(current);
                    return current;
                }
                if (previous.revision().equals(snapshot.revision())) {
                    throw new OrganizationDirectoryRevisionConflictException();
                }
                if (!snapshot.fetchedAt().isAfter(previous.fetchedAt())) {
                    throw new OrganizationDirectoryRevisionConflictException();
                }
            }
            current = OrganizationDirectoryState.active(snapshot, attemptedAt);
            persist(current);
            return current;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public OrganizationDirectoryState markFailure(String reasonCode, Instant attemptedAt) {
        String reason = reasonCode == null || reasonCode.isBlank() ? "DIRECTORY_SYNC_FAILED" : reasonCode.trim();
        if (!reason.matches("[A-Z][A-Z0-9_]{2,63}")) throw new IllegalArgumentException("directory reason is invalid");
        Instant at = attemptedAt == null ? Instant.now(clock) : attemptedAt;
        lock.writeLock().lock();
        try {
            current = current.failed(reason, at);
            persist(current);
            return current;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void reload() {
        lock.writeLock().lock();
        try {
            current = readState();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private OrganizationDirectoryState readState() {
        if (!Files.isRegularFile(statePath)) return OrganizationDirectoryState.empty();
        try {
            OrganizationDirectoryState state = objectMapper.readValue(statePath.toFile(), OrganizationDirectoryState.class);
            return state == null ? OrganizationDirectoryState.empty() : state;
        } catch (Exception exception) {
            return new OrganizationDirectoryState("FAILED", Optional.empty(), "", "", "", null, null,
                    "DIRECTORY_STATE_INVALID", 0, 0);
        }
    }

    private void persist(OrganizationDirectoryState state) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = statePath.resolveSibling(statePath.getFileName() + "." + UUID.randomUUID() + ".tmp");
            objectMapper.writeValue(temporary.toFile(), state);
            try {
                Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("organization directory state could not be persisted", exception);
        }
    }
}
