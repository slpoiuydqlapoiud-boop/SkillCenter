package com.huawei.skillcenter.execution;

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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@Conditional(ExecutionEnvironmentBackendCondition.Json.class)
public class ExecutionEnvironmentStore implements ExecutionEnvironmentRepository {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<ExecutionEnvironment> current;

    @Autowired
    public ExecutionEnvironmentStore(
            ObjectMapper objectMapper,
            @Value("${skill-center.execution-environment-storage:./data/governance/execution-environments.json}") String statePath) {
        this(Path.of(statePath), objectMapper, Clock.systemUTC());
    }

    public ExecutionEnvironmentStore(Path statePath, ObjectMapper objectMapper, Clock clock) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.current = load();
    }

    public List<ExecutionEnvironment> findAll(ExecutionEnvironmentKind kind, ExecutionEnvironmentStatus status) {
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(value -> kind == null || value.kind() == kind)
                    .filter(value -> status == null || value.status() == status)
                    .sorted(Comparator.comparing(ExecutionEnvironment::businessKey))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<ExecutionEnvironment> find(ExecutionEnvironmentKind kind, String environmentId) {
        if (kind == null || environmentId == null || environmentId.isBlank()) return Optional.empty();
        String normalizedId = environmentId.trim();
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.kind() == kind && value.environmentId().equals(normalizedId))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public ExecutionEnvironment create(ExecutionEnvironment value) {
        if (value == null) throw new IllegalArgumentException("execution environment must not be null");
        lock.writeLock().lock();
        try {
            if (current.stream().anyMatch(existing -> existing.businessKey().equals(value.businessKey()))) {
                throw new IllegalArgumentException("execution environment already exists");
            }
            List<ExecutionEnvironment> next = new ArrayList<>(current);
            next.add(value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ExecutionEnvironment replace(ExecutionEnvironment value, int expectedRevision) {
        if (value == null) throw new IllegalArgumentException("execution environment must not be null");
        if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision must be at least 1");
        lock.writeLock().lock();
        try {
            int index = -1;
            for (int currentIndex = 0; currentIndex < current.size(); currentIndex++) {
                if (current.get(currentIndex).businessKey().equals(value.businessKey())) {
                    index = currentIndex;
                    break;
                }
            }
            if (index < 0) throw new IllegalArgumentException("execution environment does not exist");
            if (current.get(index).revision() != expectedRevision
                    || value.revision() != expectedRevision + 1) {
                throw new ExecutionEnvironmentRevisionConflictException(
                        "execution environment revision conflict");
            }
            List<ExecutionEnvironment> next = new ArrayList<>(current);
            next.set(index, value);
            persist(next);
            current = List.copyOf(next);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<ExecutionEnvironment> load() {
        if (!Files.exists(statePath)) {
            List<ExecutionEnvironment> seeded = seed();
            persist(seeded);
            return List.copyOf(seeded);
        }
        try {
            List<ExecutionEnvironment> loaded = objectMapper.readValue(statePath.toFile(), new TypeReference<>() { });
            List<ExecutionEnvironment> values = loaded == null ? List.of() : List.copyOf(loaded);
            Set<String> businessKeys = new HashSet<>();
            for (ExecutionEnvironment value : values) {
                if (value == null || !businessKeys.add(value.businessKey())) {
                    throw new IllegalArgumentException("duplicate execution environment business key");
                }
            }
            return values;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to read execution environments", exception);
        }
    }

    private List<ExecutionEnvironment> seed() {
        Instant now = clock.instant();
        return List.of(
                seed("openclaw", ExecutionEnvironmentKind.AGENT_RUNTIME, "openclaw-runner", now),
                seed("mcp-network", ExecutionEnvironmentKind.MCP_SERVER, "mcp-gateway", now),
                seed("llm-gateway", ExecutionEnvironmentKind.LLM_PROVIDER, "llm-gateway", now));
    }

    private ExecutionEnvironment seed(String id, ExecutionEnvironmentKind kind, String adapterProviderId, Instant now) {
        return new ExecutionEnvironment(id, kind, "context-v1", ExecutionEnvironmentStatus.ACTIVE,
                List.of("context-only"), adapterProviderId, "", "system", now, "system", now);
    }

    private void persist(List<ExecutionEnvironment> values) {
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
            throw new ExecutionEnvironmentPersistenceException(exception);
        }
    }

    public static class ExecutionEnvironmentPersistenceException extends RuntimeException {
        public ExecutionEnvironmentPersistenceException(Throwable cause) {
            super(cause);
        }
    }
}
