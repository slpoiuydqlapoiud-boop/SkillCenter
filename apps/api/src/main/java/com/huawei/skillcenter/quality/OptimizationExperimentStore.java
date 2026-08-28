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
@Conditional(OptimizationExperimentBackendCondition.Json.class)
public class OptimizationExperimentStore implements OptimizationExperimentRepository {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<OptimizationExperiment> current;

    @Autowired
    public OptimizationExperimentStore(ObjectMapper objectMapper,
                                       @Value("${skill-center.optimization-experiment-storage:./data/governance/optimization-experiments.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    OptimizationExperimentStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.current = load();
    }

    public List<OptimizationExperiment> findAll(String skillId, String workItemId, String status) {
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(value -> skillId == null || skillId.isBlank() || skillId.equals(value.skillId()))
                    .filter(value -> workItemId == null || workItemId.isBlank() || workItemId.equals(value.workItemId()))
                    .filter(value -> status == null || status.isBlank()
                            || OptimizationExperimentStatus.normalize(status).equals(value.status()))
                    .sorted(Comparator.comparing(OptimizationExperiment::updatedAt).reversed())
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<OptimizationExperiment> find(String experimentId) {
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.experimentId().equals(experimentId)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<OptimizationExperiment> findActiveByWorkItemId(String workItemId) {
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(value -> value.workItemId().equals(workItemId)
                            && !OptimizationExperimentStatus.isTerminal(value.status()))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public OptimizationExperiment create(OptimizationExperiment value) {
        if (value == null) throw new IllegalArgumentException("experiment must not be null");
        lock.writeLock().lock();
        try {
            if (current.stream().anyMatch(existing -> existing.experimentId().equals(value.experimentId()))) {
                throw new OptimizationExperimentConflictException("experimentId already exists");
            }
            ensureNoActiveExperiment(current, value);
            List<OptimizationExperiment> next = new ArrayList<>(current);
            next.add(value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public OptimizationExperiment replace(OptimizationExperiment value) {
        if (value == null) throw new IllegalArgumentException("experiment must not be null");
        lock.writeLock().lock();
        try {
            int index = -1;
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).experimentId().equals(value.experimentId())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) throw new OptimizationExperimentNotFoundException(value.experimentId());
            OptimizationExperiment existing = current.get(index);
            if (existing.decision() != null && !existing.decision().equals(value.decision())) {
                throw new OptimizationExperimentConflictException("decision snapshot is immutable");
            }
            List<OptimizationExperiment> next = new ArrayList<>(current);
            next.remove(index);
            ensureNoActiveExperiment(next, value);
            next.add(value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void ensureNoActiveExperiment(List<OptimizationExperiment> values, OptimizationExperiment value) {
        if (OptimizationExperimentStatus.isTerminal(value.status())) return;
        boolean duplicate = values.stream().anyMatch(existing -> !OptimizationExperimentStatus.isTerminal(existing.status())
                && existing.workItemId().equals(value.workItemId()));
        if (duplicate) throw new OptimizationExperimentConflictException("active experiment already exists for work item");
    }

    private List<OptimizationExperiment> load() {
        if (!Files.exists(statePath)) return List.of();
        try {
            List<OptimizationExperiment> loaded = objectMapper.readValue(statePath.toFile(), new TypeReference<>() { });
            List<OptimizationExperiment> values = List.copyOf(loaded == null ? List.of() : loaded);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < values.size(); i++) {
                OptimizationExperiment value = values.get(i);
                if (!ids.add(value.experimentId())) throw new IllegalArgumentException("duplicate experimentId");
                ensureNoActiveExperiment(values.subList(0, i), value);
            }
            return values;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof OptimizationExperimentPersistenceException persistence) throw persistence;
            throw new OptimizationExperimentPersistenceException(exception);
        }
    }

    private void persist(List<OptimizationExperiment> values) {
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
            throw new OptimizationExperimentPersistenceException(exception);
        }
    }
}
