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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class OptimizationSuggestionDispositionStore {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<OptimizationSuggestionDisposition> current;

    @Autowired
    public OptimizationSuggestionDispositionStore(
            ObjectMapper objectMapper,
            @Value("${skill-center.optimization-disposition-storage:./data/governance/optimization-dispositions.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    OptimizationSuggestionDispositionStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.current = load();
    }

    public List<OptimizationSuggestionDisposition> findAll(String skillId, String version) {
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.skillId().equals(skillId) && value.version().equals(version)).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<OptimizationSuggestionDisposition> find(String skillId, String version, String suggestionId) {
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.skillId().equals(skillId)
                    && value.version().equals(version) && value.suggestionId().equals(suggestionId)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public OptimizationSuggestionDisposition upsert(OptimizationSuggestionDisposition value) {
        if (value == null) {
            throw new IllegalArgumentException("disposition must not be null");
        }
        lock.writeLock().lock();
        try {
            List<OptimizationSuggestionDisposition> next = new ArrayList<>(current);
            next.removeIf(existing -> existing.skillId().equals(value.skillId())
                    && existing.version().equals(value.version())
                    && existing.suggestionId().equals(value.suggestionId()));
            next.add(value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<OptimizationSuggestionDisposition> load() {
        if (!Files.exists(statePath)) {
            return List.of();
        }
        try {
            List<OptimizationSuggestionDisposition> loaded = objectMapper.readValue(
                    statePath.toFile(), new TypeReference<>() {});
            List<OptimizationSuggestionDisposition> values = List.copyOf(loaded == null ? List.of() : loaded);
            Set<String> keys = new HashSet<>();
            for (OptimizationSuggestionDisposition value : values) {
                String key = value.skillId() + "\u0000" + value.version() + "\u0000" + value.suggestionId();
                if (!keys.add(key)) {
                    throw new IllegalArgumentException("duplicate optimization disposition key");
                }
            }
            return values;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to read optimization suggestion dispositions", exception);
        }
    }

    private void persist(List<OptimizationSuggestionDisposition> values) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), values);
            try {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new OptimizationSuggestionDispositionPersistenceException(exception);
        }
    }

    public static class OptimizationSuggestionDispositionPersistenceException extends RuntimeException {
        public OptimizationSuggestionDispositionPersistenceException(Throwable cause) {
            super(cause);
        }
    }
}
