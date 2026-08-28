package com.huawei.skillcenter.operations;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@ConditionalOnProperty(name = "skill-center.runtime-summary-backend", havingValue = "json", matchIfMissing = true)
public class RuntimeSummaryStore implements RuntimeSummaryRepository, RuntimeSummaryBackendHealth {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final boolean persistent;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<RuntimeSummary> current;

    public RuntimeSummaryStore() {
        this(null, new ObjectMapper().findAndRegisterModules(), false);
    }

    @Autowired
    public RuntimeSummaryStore(ObjectMapper objectMapper,
                               @Value("${skill-center.runtime-summary-storage:./data/governance/runtime-summaries.json}") String statePath) {
        this(Path.of(statePath), objectMapper, true);
    }

    public RuntimeSummaryStore(Path statePath, ObjectMapper objectMapper) {
        this(statePath, objectMapper, true);
    }

    private RuntimeSummaryStore(Path statePath, ObjectMapper objectMapper, boolean persistent) {
        this.statePath = statePath == null ? null : statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.persistent = persistent;
        this.current = load();
    }

    public boolean putIfAbsent(RuntimeSummary summary) {
        if (summary == null || summary.eventId() == null) {
            throw new IllegalArgumentException("runtime summary and eventId are required");
        }
        validateSummary(summary);
        lock.writeLock().lock();
        try {
            RuntimeSummary existing = current.stream().filter(item -> summary.eventId().equals(item.eventId())).findFirst().orElse(null);
            if (existing != null) {
                if (!existing.equals(summary)) {
                    throw new RuntimeSummaryConflictException("eventId already exists with different content");
                }
                return true;
            }
            List<RuntimeSummary> next = new ArrayList<>(current);
            next.add(summary);
            persist(next);
            current = List.copyOf(next);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<RuntimeSummary> findAll() {
        lock.readLock().lock();
        try {
            return current.stream().sorted(Comparator.comparing(RuntimeSummary::occurredAt)
                    .thenComparing(RuntimeSummary::eventId)).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long countBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        return findAll().stream().filter(item -> item.occurredAt().toInstant().isBefore(cutoff)).count();
    }

    public int deleteBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        lock.writeLock().lock();
        try {
            List<RuntimeSummary> next = current.stream()
                    .filter(item -> !item.occurredAt().toInstant().isBefore(cutoff)).toList();
            int removed = current.size() - next.size();
            if (removed > 0) {
                persist(next);
                current = List.copyOf(next);
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            if (!current.isEmpty()) {
                persist(List.of());
                current = List.of();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public RuntimeSummaryReadiness readiness() {
        return new RuntimeSummaryReadiness("json", "DEGRADED", "RUNTIME_SUMMARY_JSON_ONLY",
                "运行摘要使用本地 JSON，未提供多实例共享存储");
    }

    private List<RuntimeSummary> load() {
        if (!persistent || statePath == null || !Files.exists(statePath)) return List.of();
        try {
            List<RuntimeSummary> values = objectMapper.readValue(statePath.toFile(), new TypeReference<>() {});
            List<RuntimeSummary> loaded = List.copyOf(values == null ? List.of() : values);
            validateLoaded(loaded);
            return loaded;
        } catch (IOException | RuntimeException exception) {
            throw new RuntimeSummaryPersistenceException(exception);
        }
    }

    private void validateLoaded(List<RuntimeSummary> values) {
        java.util.Set<UUID> eventIds = new java.util.HashSet<>();
        for (RuntimeSummary value : values) {
            validateSummary(value);
            if (!eventIds.add(value.eventId())) {
                throw new IllegalArgumentException("persisted runtime summary eventId is duplicated");
            }
            boolean needsError = "failure".equals(value.status()) || "timeout".equals(value.status());
            if (needsError != (value.errorCode() != null && !value.errorCode().isBlank())) {
                throw new IllegalArgumentException("persisted runtime summary errorCode is invalid");
            }
        }
    }

    private void validateSummary(RuntimeSummary value) {
        if (value == null || !"1.0".equals(value.schemaVersion()) || value.eventId() == null
                || value.occurredAt() == null || blank(value.skillId()) || blank(value.version())
                || !java.util.Set.of("success", "failure", "timeout", "cancelled").contains(value.status())
                || value.durationMs() < 0 || value.durationMs() > 86_400_000L) {
            throw new IllegalArgumentException("runtime summary is invalid");
        }
        if (!java.util.Set.of("mock", "production").contains(value.dataSource())) {
            throw new IllegalArgumentException("runtime summary dataSource is invalid");
        }
        boolean needsError = "failure".equals(value.status()) || "timeout".equals(value.status());
        if (needsError != (value.errorCode() != null && !value.errorCode().isBlank())) {
            throw new IllegalArgumentException("runtime summary errorCode is invalid");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void persist(List<RuntimeSummary> values) {
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
            throw new RuntimeSummaryPersistenceException(exception);
        }
    }

    public static class RuntimeSummaryPersistenceException extends RuntimeException {
        public RuntimeSummaryPersistenceException(Throwable cause) { super(cause); }
    }
}
