package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class SkillExecutionStore {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final boolean persistent;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<SkillExecutionRecord> current;

    public SkillExecutionStore() {
        this(null, new ObjectMapper().findAndRegisterModules(), false);
    }

    @Autowired
    public SkillExecutionStore(ObjectMapper objectMapper,
                               @Value("${skill-center.execution-storage:./data/governance/skill-executions.json}") String statePath) {
        this(Path.of(statePath), objectMapper, true);
    }

    SkillExecutionStore(Path statePath, ObjectMapper objectMapper) {
        this(statePath, objectMapper, true);
    }

    private SkillExecutionStore(Path statePath, ObjectMapper objectMapper, boolean persistent) {
        this.statePath = statePath == null ? null : statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.persistent = persistent;
        this.current = load();
    }

    public SkillExecutionRecord save(SkillExecutionRecord record) {
        if (record == null) throw new IllegalArgumentException("execution record is required");
        validateRecord(record);
        lock.writeLock().lock();
        try {
            SkillExecutionRecord existing = current.stream()
                    .filter(item -> item.executionId().equals(record.executionId())).findFirst().orElse(null);
            if (existing != null) {
                if (existing.equals(record)) return existing;
                throw new SkillExecutionConflictException("executionId already exists with different content");
            }
            List<SkillExecutionRecord> next = new ArrayList<>(current);
            next.add(record);
            persist(next);
            current = List.copyOf(next);
            return record;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<SkillExecutionRecord> find(UUID executionId) {
        lock.readLock().lock();
        try {
            return current.stream().filter(record -> record.executionId().equals(executionId)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<SkillExecutionRecord> findAll(String skillId) {
        return findAll(skillId, null, null, null, null);
    }

    public List<SkillExecutionRecord> findAll(String skillId, String dataSource,
                                               String runtimeId, String mcpServerId, String llmProviderId) {
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(record -> skillId == null || skillId.isBlank() || skillId.equals(record.skillId()))
                    .filter(record -> dataSource == null || dataSource.isBlank() || dataSource.equals(record.dataSource()))
                    .filter(record -> runtimeId == null || runtimeId.isBlank() || runtimeId.equals(record.runtimeId()))
                    .filter(record -> mcpServerId == null || mcpServerId.isBlank() || mcpServerId.equals(record.mcpServerId()))
                    .filter(record -> llmProviderId == null || llmProviderId.isBlank() || llmProviderId.equals(record.llmProviderId()))
                    .sorted(Comparator.comparing(SkillExecutionRecord::executedAt).reversed())
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long countBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        lock.readLock().lock();
        try {
            return current.stream().filter(record -> record.executedAt() != null && record.executedAt().isBefore(cutoff)).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long deleteBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        lock.writeLock().lock();
        try {
            List<SkillExecutionRecord> next = current.stream()
                    .filter(record -> record.executedAt() == null || !record.executedAt().isBefore(cutoff))
                    .toList();
            long removed = current.size() - next.size();
            if (removed > 0) persist(next);
            current = List.copyOf(next);
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            persist(List.of());
            current = List.of();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<SkillExecutionRecord> load() {
        if (!persistent || statePath == null || !Files.exists(statePath)) return List.of();
        try {
            List<SkillExecutionRecord> values = objectMapper.readValue(statePath.toFile(), new TypeReference<>() { });
            List<SkillExecutionRecord> loaded = List.copyOf(values == null ? List.of() : values);
            validateLoaded(loaded);
            return loaded;
        } catch (IOException | RuntimeException exception) {
            throw new SkillExecutionPersistenceException(exception);
        }
    }

    private void validateLoaded(List<SkillExecutionRecord> values) {
        java.util.Set<UUID> executionIds = new java.util.HashSet<>();
        for (SkillExecutionRecord record : values) {
            validateRecord(record);
            if (!executionIds.add(record.executionId())) {
                throw new IllegalArgumentException("persisted runner execution id is duplicated");
            }
        }
    }

    private void validateRecord(SkillExecutionRecord record) {
        if (record == null || record.executionId() == null || record.skillId() == null || record.skillId().isBlank()
                || record.skillVersion() == null || record.skillVersion().isBlank() || record.status() == null
                || record.executedAt() == null || record.durationMs() < 0 || record.durationMs() > 86_400_000L
                || !java.util.Set.of("mock", "production").contains(record.dataSource())) {
            throw new IllegalArgumentException("runner execution record is invalid");
        }
        boolean needsError = record.status() == RunnerExecutionStatus.FAILED
                || record.status() == RunnerExecutionStatus.TIMED_OUT;
        if (needsError != (record.errorCode() != null && !record.errorCode().isBlank())) {
            throw new IllegalArgumentException("runner execution errorCode is invalid");
        }
    }

    private void persist(List<SkillExecutionRecord> values) {
        if (!persistent || statePath == null) return;
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
            throw new SkillExecutionPersistenceException(exception);
        }
    }

    public static class SkillExecutionPersistenceException extends RuntimeException {
        public SkillExecutionPersistenceException(Throwable cause) { super(cause); }
    }

    public static class SkillExecutionConflictException extends RuntimeException {
        public SkillExecutionConflictException(String message) { super(message); }
    }
}
