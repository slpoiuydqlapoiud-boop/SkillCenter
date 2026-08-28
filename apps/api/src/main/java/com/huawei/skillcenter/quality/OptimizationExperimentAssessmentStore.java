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
public class OptimizationExperimentAssessmentStore implements OptimizationExperimentAssessmentRepository {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<OptimizationExperimentAssessment> current;

    @Autowired
    public OptimizationExperimentAssessmentStore(ObjectMapper objectMapper,
                                                 @Value("${skill-center.optimization-experiment-assessment-storage:./data/governance/optimization-experiment-assessments.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    OptimizationExperimentAssessmentStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.current = load();
    }

    public List<OptimizationExperimentAssessment> findAll(String experimentId) {
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(value -> experimentId == null || experimentId.isBlank()
                            || experimentId.equals(value.experimentId()))
                    .sorted(Comparator.comparing(OptimizationExperimentAssessment::assessedAt).reversed())
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<OptimizationExperimentAssessment> find(String assessmentId) {
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.assessmentId().equals(assessmentId)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long countBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff must not be null");
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.assessedAt().isBefore(cutoff)).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long deleteBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff must not be null");
        lock.writeLock().lock();
        try {
            List<OptimizationExperimentAssessment> retained = current.stream()
                    .filter(value -> !value.assessedAt().isBefore(cutoff)).toList();
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

    public OptimizationExperimentAssessment create(OptimizationExperimentAssessment value) {
        if (value == null) throw new IllegalArgumentException("assessment must not be null");
        lock.writeLock().lock();
        try {
            if (current.stream().anyMatch(existing -> existing.assessmentId().equals(value.assessmentId()))) {
                throw new OptimizationExperimentConflictException("assessmentId already exists");
            }
            List<OptimizationExperimentAssessment> next = new ArrayList<>(current);
            next.add(value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<OptimizationExperimentAssessment> load() {
        if (!Files.exists(statePath)) return List.of();
        try {
            List<OptimizationExperimentAssessment> loaded = objectMapper.readValue(statePath.toFile(), new TypeReference<>() { });
            List<OptimizationExperimentAssessment> values = List.copyOf(loaded == null ? List.of() : loaded);
            Set<String> ids = new HashSet<>();
            for (OptimizationExperimentAssessment value : values) {
                if (!ids.add(value.assessmentId())) throw new IllegalArgumentException("duplicate assessmentId");
            }
            return values;
        } catch (IOException | RuntimeException exception) {
            throw new OptimizationExperimentAssessmentPersistenceException(exception);
        }
    }

    private void persist(List<OptimizationExperimentAssessment> values) {
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
            throw new OptimizationExperimentAssessmentPersistenceException(exception);
        }
    }

    public static class OptimizationExperimentAssessmentPersistenceException extends RuntimeException {
        public OptimizationExperimentAssessmentPersistenceException(Throwable cause) {
            super(cause);
        }
    }
}
