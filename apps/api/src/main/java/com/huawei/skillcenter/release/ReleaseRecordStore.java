package com.huawei.skillcenter.release;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@ConditionalOnProperty(name = "skill-center.release-backend", havingValue = "json", matchIfMissing = true)
public class ReleaseRecordStore implements ReleaseRecordRepository {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<ReleaseRecord> current;

    @Autowired
    public ReleaseRecordStore(ObjectMapper objectMapper,
                              @Value("${skill-center.release-storage:./data/governance/releases.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    ReleaseRecordStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.current = load();
    }

    public List<ReleaseRecord> findAll(String skillId, String version,
                                       ReleaseEnvironment environment, ReleaseStatus status) {
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(value -> skillId == null || skillId.isBlank() || skillId.equals(value.skillId()))
                    .filter(value -> version == null || version.isBlank() || version.equals(value.version()))
                    .filter(value -> environment == null || environment == value.targetEnvironment())
                    .filter(value -> status == null || status == value.status())
                    .sorted(Comparator.comparing(ReleaseRecord::updatedAt).reversed()
                            .thenComparing(ReleaseRecord::releaseId))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<ReleaseRecord> find(String releaseId) {
        if (releaseId == null || releaseId.isBlank()) return Optional.empty();
        String normalizedId = releaseId.trim();
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.releaseId().equals(normalizedId)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<ReleaseRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        String normalizedKey = idempotencyKey.trim();
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.idempotencyKey().equals(normalizedKey)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<ReleaseRecord> findActiveBusinessKey(String skillId, String version,
                                                         ReleaseEnvironment environment) {
        if (skillId == null || skillId.isBlank() || version == null || version.isBlank() || environment == null) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(value -> value.skillId().equals(skillId.trim())
                            && value.version().equals(version.trim())
                            && value.targetEnvironment() == environment
                            && !value.status().terminal())
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public ReleaseRecord create(ReleaseRecord value) {
        if (value == null) throw new IllegalArgumentException("release must not be null");
        lock.writeLock().lock();
        try {
            ensureNoDuplicate(current, value);
            List<ReleaseRecord> next = new ArrayList<>(current);
            next.add(value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ReleaseRecord replace(ReleaseRecord value) {
        if (value == null) throw new IllegalArgumentException("release must not be null");
        lock.writeLock().lock();
        try {
            int index = indexOf(value.releaseId());
            if (index < 0) throw new IllegalArgumentException("release does not exist");
            ReleaseRecord existing = current.get(index);
            ensureImmutableContext(existing, value);
            List<ReleaseRecord> next = new ArrayList<>(current);
            next.remove(index);
            ensureNoDuplicate(next, value);
            next.add(value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private int indexOf(String releaseId) {
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).releaseId().equals(releaseId)) return i;
        }
        return -1;
    }

    private void ensureNoDuplicate(List<ReleaseRecord> values, ReleaseRecord value) {
        if (values.stream().anyMatch(existing -> existing.releaseId().equals(value.releaseId()))) {
            throw new ReleaseConflictException("releaseId already exists");
        }
        if (values.stream().anyMatch(existing -> existing.idempotencyKey().equals(value.idempotencyKey()))) {
            throw new ReleaseConflictException("idempotencyKey already exists");
        }
        if (!value.status().terminal() && values.stream().anyMatch(existing -> !existing.status().terminal()
                && existing.skillId().equals(value.skillId())
                && existing.version().equals(value.version())
                && existing.targetEnvironment() == value.targetEnvironment())) {
            throw new ReleaseConflictException("active release already exists for skill version and environment");
        }
    }

    private void ensureImmutableContext(ReleaseRecord existing, ReleaseRecord value) {
        if (!existing.skillId().equals(value.skillId()) || !existing.version().equals(value.version())
                || !existing.sha256().equals(value.sha256())
                || existing.targetEnvironment() != value.targetEnvironment()
                || !existing.gateSnapshot().equals(value.gateSnapshot())
                || !existing.sourceAssessmentId().equals(value.sourceAssessmentId())
                || !existing.rollbackOfReleaseId().equals(value.rollbackOfReleaseId())
                || !existing.rollbackTargetVersion().equals(value.rollbackTargetVersion())
                || !existing.rollbackTargetReleaseId().equals(value.rollbackTargetReleaseId())
                || !existing.idempotencyKey().equals(value.idempotencyKey())
                || !existing.requestedBy().equals(value.requestedBy())
                || !existing.requestedAt().equals(value.requestedAt())) {
            throw new ReleaseConflictException("release context is immutable");
        }
    }

    private List<ReleaseRecord> load() {
        if (!Files.exists(statePath)) return List.of();
        try {
            List<ReleaseRecord> loaded = objectMapper.readValue(statePath.toFile(), new TypeReference<>() { });
            List<ReleaseRecord> values = List.copyOf(loaded == null ? List.of() : loaded);
            Set<String> releaseIds = new HashSet<>();
            Set<String> idempotencyKeys = new HashSet<>();
            List<ReleaseRecord> checked = new ArrayList<>();
            for (ReleaseRecord value : values) {
                if (value == null || !releaseIds.add(value.releaseId()) || !idempotencyKeys.add(value.idempotencyKey())) {
                    throw new IllegalArgumentException("duplicate release identity");
                }
                ensureNoDuplicate(checked, value);
                checked.add(value);
            }
            return List.copyOf(values);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ReleasePersistenceException persistence) throw persistence;
            throw new ReleasePersistenceException(exception);
        }
    }

    private void persist(List<ReleaseRecord> values) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), values);
            try {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new ReleasePersistenceException(exception);
        }
    }

}
