package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
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
@Conditional(OptimizationWorkItemBackendCondition.Json.class)
public class OptimizationWorkItemStore implements OptimizationWorkItemRepository {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<OptimizationWorkItem> current;

    @Autowired
    public OptimizationWorkItemStore(ObjectMapper objectMapper,
                                     @Value("${skill-center.optimization-work-item-storage:./data/governance/optimization-work-items.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    OptimizationWorkItemStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.current = load();
    }

    public List<OptimizationWorkItem> findAll(String skillId, String status, String ownerId, String sourceVersion) {
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(value -> skillId == null || skillId.isBlank() || skillId.equals(value.skillId()))
                    .filter(value -> status == null || status.isBlank() || OptimizationWorkItemStatus.normalize(status).equals(value.status()))
                    .filter(value -> ownerId == null || ownerId.isBlank() || ownerId.equals(value.ownerId()))
                    .filter(value -> sourceVersion == null || sourceVersion.isBlank() || sourceVersion.equals(value.sourceVersion()))
                    .sorted(Comparator.comparing(OptimizationWorkItem::updatedAt).reversed())
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<OptimizationWorkItem> find(String workItemId) {
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.workItemId().equals(workItemId)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public OptimizationWorkItem create(OptimizationWorkItem value) {
        if (value == null) throw new IllegalArgumentException("work item must not be null");
        lock.writeLock().lock();
        try {
            if (current.stream().anyMatch(existing -> existing.workItemId().equals(value.workItemId()))) {
                throw new IllegalArgumentException("workItemId already exists");
            }
            ensureNoActiveBusinessKey(current, value);
            List<OptimizationWorkItem> next = new ArrayList<>(current);
            next.add(value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public OptimizationWorkItem replace(OptimizationWorkItem value) {
        if (value == null) throw new IllegalArgumentException("work item must not be null");
        lock.writeLock().lock();
        try {
            int index = -1;
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).workItemId().equals(value.workItemId())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) throw new IllegalArgumentException("workItemId does not exist");
            List<OptimizationWorkItem> next = new ArrayList<>(current);
            next.remove(index);
            ensureNoActiveBusinessKey(next, value);
            next.add(value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void ensureNoActiveBusinessKey(List<OptimizationWorkItem> values, OptimizationWorkItem value) {
        if (OptimizationWorkItemStatus.isTerminal(value.status())) return;
        boolean duplicate = values.stream().anyMatch(existing -> !OptimizationWorkItemStatus.isTerminal(existing.status())
                && existing.skillId().equals(value.skillId())
                && existing.sourceVersion().equals(value.sourceVersion())
                && existing.suggestionId().equals(value.suggestionId()));
        if (duplicate) throw new IllegalArgumentException("active work item already exists");
    }

    private List<OptimizationWorkItem> load() {
        if (!Files.exists(statePath)) return List.of();
        try {
            List<OptimizationWorkItem> loaded = objectMapper.readValue(statePath.toFile(), new TypeReference<>() {});
            List<OptimizationWorkItem> values = List.copyOf(loaded == null ? List.of() : loaded);
            Set<String> ids = new HashSet<>();
            for (OptimizationWorkItem value : values) {
                if (!ids.add(value.workItemId())) throw new IllegalArgumentException("duplicate workItemId");
            }
            for (int i = 0; i < values.size(); i++) {
                ensureNoActiveBusinessKey(values.subList(0, i), values.get(i));
            }
            return values;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to read optimization work items", exception);
        }
    }

    private void persist(List<OptimizationWorkItem> values) {
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
            throw new OptimizationWorkItemPersistenceException(exception);
        }
    }

    public static class OptimizationWorkItemPersistenceException extends RuntimeException {
        public OptimizationWorkItemPersistenceException(Throwable cause) {
            super(cause);
        }
    }
}
