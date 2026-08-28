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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@Conditional(BenchmarkBackendCondition.Json.class)
public class BenchmarkStore implements BenchmarkRepository {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<BenchmarkResult> current;

    @Autowired
    public BenchmarkStore(ObjectMapper objectMapper,
                          @Value("${skill-center.benchmark-storage:./data/governance/benchmarks.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    BenchmarkStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.current = load();
    }

    public List<BenchmarkResult> findAll(String skillId) {
        lock.readLock().lock();
        try {
            return current.stream().filter(result -> skillId == null || skillId.isBlank() || result.skillId().equals(skillId))
                    .sorted(Comparator.comparing(BenchmarkResult::createdAt).reversed()).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public BenchmarkResult findByExperimentId(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) return null;
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(result -> experimentId.trim().equals(result.experimentId()))
                    .findFirst()
                    .orElse(null);
        } finally {
            lock.readLock().unlock();
        }
    }

    public BenchmarkResult add(BenchmarkResult result) {
        if (result == null) throw new IllegalArgumentException("benchmark result must not be null");
        lock.writeLock().lock();
        try {
            if (current.stream().anyMatch(existing -> existing.benchmarkId().equals(result.benchmarkId()))) {
                throw new IllegalArgumentException("benchmarkId already exists: " + result.benchmarkId());
            }
            validate(result);
            List<BenchmarkResult> next = new ArrayList<>(current);
            next.add(result);
            persist(next);
            current = List.copyOf(next);
            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public long countBefore(Instant cutoff) {
        return countBefore(cutoff, Set.of());
    }

    public long countBefore(Instant cutoff, Set<String> protectedBenchmarkIds) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        Set<String> protectedIds = protectedBenchmarkIds == null ? Set.of() : Set.copyOf(protectedBenchmarkIds);
        lock.readLock().lock();
        try {
            return current.stream().filter(result -> result.createdAt() != null
                    && result.createdAt().isBefore(cutoff)
                    && !protectedIds.contains(result.benchmarkId())).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int deleteBefore(Instant cutoff) {
        return deleteBefore(cutoff, Set.of());
    }

    public int deleteBefore(Instant cutoff, Set<String> protectedBenchmarkIds) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        Set<String> protectedIds = protectedBenchmarkIds == null ? Set.of() : Set.copyOf(protectedBenchmarkIds);
        lock.writeLock().lock();
        try {
            List<BenchmarkResult> next = current.stream()
                    .filter(result -> result.createdAt() == null || !result.createdAt().isBefore(cutoff)
                            || protectedIds.contains(result.benchmarkId()))
                    .toList();
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

    private List<BenchmarkResult> load() {
        if (!Files.exists(statePath)) return List.of();
        try {
            List<BenchmarkResult> values = objectMapper.readValue(statePath.toFile(), new TypeReference<>() {});
            List<BenchmarkResult> normalized = List.copyOf(values == null ? List.of() : values);
            java.util.Set<String> ids = new java.util.HashSet<>();
            java.util.Set<String> experimentIds = new java.util.HashSet<>();
            normalized.forEach(value -> {
                validate(value);
                if (!ids.add(value.benchmarkId())) {
                    throw new IllegalArgumentException("duplicate benchmarkId: " + value.benchmarkId());
                }
                if (!value.experimentId().isBlank() && !experimentIds.add(value.experimentId())) {
                    throw new IllegalArgumentException("duplicate benchmark experimentId: " + value.experimentId());
                }
            });
            return normalized;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to read benchmark state", exception);
        }
    }

    static void validate(BenchmarkResult result) {
        if (result == null) throw new IllegalArgumentException("benchmark result must not be null");
        if (result.baselineVersion().equals(result.candidateVersion())) {
            throw new IllegalArgumentException("baselineVersion and candidateVersion must differ");
        }
        QualityComparison comparison = result.comparison();
        if (comparison != null && (!result.skillId().equals(comparison.skillId())
                || !result.baselineVersion().equals(comparison.baselineVersion())
                || !result.candidateVersion().equals(comparison.candidateVersion()))) {
            throw new IllegalArgumentException("benchmark comparison context does not match result");
        }
    }

    private void persist(List<BenchmarkResult> values) {
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
            throw new BenchmarkPersistenceException(exception);
        }
    }

    public static class BenchmarkPersistenceException extends RuntimeException {
        public BenchmarkPersistenceException(Throwable cause) { super(cause); }
    }
}
