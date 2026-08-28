package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** JSON implementation retained as the safe local default and compatibility format. */
@Component
@Conditional(GovernanceBackendCondition.Json.class)
public class JsonGovernanceStateRepository implements GovernanceStateRepository {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Autowired
    public JsonGovernanceStateRepository(ObjectMapper objectMapper,
                                         @Value("${skill-center.governance-storage:./data/governance/state.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    public JsonGovernanceStateRepository(Path statePath, ObjectMapper objectMapper) {
        if (statePath == null || objectMapper == null) {
            throw new IllegalArgumentException("statePath and objectMapper are required");
        }
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<GovernanceState> load() {
        lock.readLock().lock();
        try {
            return Files.exists(statePath)
                    ? Optional.of(new GovernanceState(0, readSnapshot()))
                    : Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public GovernanceState loadOrSeed(Supplier<GovernanceSnapshot> seed) {
        if (seed == null) throw new IllegalArgumentException("seed must not be null");
        lock.writeLock().lock();
        try {
            if (Files.exists(statePath)) {
                return new GovernanceState(0, readSnapshot());
            }
            GovernanceSnapshot snapshot = seed.get();
            if (snapshot == null) throw new IllegalArgumentException("seed snapshot must not be null");
            writeSnapshot(snapshot);
            return new GovernanceState(0, snapshot);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public GovernanceState replace(long expectedRevision, GovernanceSnapshot snapshot) {
        if (expectedRevision != 0) {
            throw new GovernanceStateConflictException("JSON governance state does not support a non-zero revision");
        }
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        lock.writeLock().lock();
        try {
            writeSnapshot(snapshot);
            return new GovernanceState(0, snapshot);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private GovernanceSnapshot readSnapshot() {
        try {
            return objectMapper.readValue(statePath.toFile(), GovernanceSnapshot.class);
        } catch (IOException | RuntimeException exception) {
            throw new GovernanceStore.GovernancePersistenceException(exception);
        }
    }

    private void writeSnapshot(GovernanceSnapshot snapshot) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot);
            try {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof GovernanceStore.GovernancePersistenceException persistence) throw persistence;
            throw new GovernanceStore.GovernancePersistenceException(exception);
        }
    }
}
