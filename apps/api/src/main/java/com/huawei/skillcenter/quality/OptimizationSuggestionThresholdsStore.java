package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class OptimizationSuggestionThresholdsStore {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private OptimizationSuggestionThresholds current;

    @Autowired
    public OptimizationSuggestionThresholdsStore(
            ObjectMapper objectMapper,
            @Value("${skill-center.optimization-thresholds-storage:./data/governance/optimization-thresholds.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    OptimizationSuggestionThresholdsStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.current = load();
    }

    public OptimizationSuggestionThresholds get() {
        lock.readLock().lock();
        try {
            return current;
        } finally {
            lock.readLock().unlock();
        }
    }

    public OptimizationSuggestionThresholds update(OptimizationSuggestionThresholds value) {
        if (value == null) {
            throw new IllegalArgumentException("thresholds must not be null");
        }
        lock.writeLock().lock();
        try {
            persist(value);
            current = value;
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private OptimizationSuggestionThresholds load() {
        if (!Files.exists(statePath)) {
            return OptimizationSuggestionThresholds.defaults();
        }
        try {
            return objectMapper.readValue(statePath.toFile(), OptimizationSuggestionThresholds.class);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to read optimization suggestion thresholds", exception);
        }
    }

    private void persist(OptimizationSuggestionThresholds value) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new OptimizationSuggestionThresholdsPersistenceException(exception);
        }
    }

    public static class OptimizationSuggestionThresholdsPersistenceException extends RuntimeException {
        public OptimizationSuggestionThresholdsPersistenceException(Throwable cause) {
            super(cause);
        }
    }
}
