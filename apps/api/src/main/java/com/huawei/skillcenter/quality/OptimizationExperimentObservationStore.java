package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
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
public class OptimizationExperimentObservationStore implements OptimizationExperimentObservationRepository {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<OptimizationExperimentObservation> current;

    @Autowired
    public OptimizationExperimentObservationStore(ObjectMapper objectMapper,
                                                  @Value("${skill-center.optimization-experiment-observation-storage:./data/governance/optimization-experiment-observations.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    OptimizationExperimentObservationStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.current = load();
    }

    public List<OptimizationExperimentObservation> findAll(String experimentId) {
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(value -> experimentId == null || experimentId.isBlank()
                            || experimentId.equals(value.experimentId()))
                    .sorted(Comparator.comparing(OptimizationExperimentObservation::capturedAt).reversed())
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<OptimizationExperimentObservation> find(String observationId) {
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.observationId().equals(observationId)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long countBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff must not be null");
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.capturedAt().isBefore(cutoff)).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long deleteBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff must not be null");
        lock.writeLock().lock();
        try {
            List<OptimizationExperimentObservation> retained = current.stream()
                    .filter(value -> !value.capturedAt().isBefore(cutoff)).toList();
            long deleted = current.size() - retained.size();
            if (deleted > 0) {
                persist(retained);
                current = List.copyOf(retained);
            }
            return deleted;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public OptimizationExperimentObservation create(OptimizationExperimentObservation value) {
        if (value == null) throw new IllegalArgumentException("observation must not be null");
        lock.writeLock().lock();
        try {
            if (current.stream().anyMatch(existing -> existing.observationId().equals(value.observationId()))) {
                throw new OptimizationExperimentConflictException("observationId already exists");
            }
            List<OptimizationExperimentObservation> next = new ArrayList<>(current);
            next.add(value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<OptimizationExperimentObservation> load() {
        if (!Files.exists(statePath)) return List.of();
        try {
            List<OptimizationExperimentObservation> loaded = objectMapper.readValue(statePath.toFile(), new TypeReference<>() { });
            List<OptimizationExperimentObservation> values = List.copyOf(loaded == null ? List.of() : loaded);
            Set<String> ids = new HashSet<>();
            for (OptimizationExperimentObservation value : values) {
                if (!ids.add(value.observationId())) throw new IllegalArgumentException("duplicate observationId");
            }
            return values;
        } catch (IOException | RuntimeException exception) {
            throw new OptimizationExperimentPersistenceException(exception);
        }
    }

    private void persist(List<OptimizationExperimentObservation> values) {
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
