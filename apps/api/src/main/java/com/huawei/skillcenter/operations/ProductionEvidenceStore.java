package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** JSON-default atomic store for safe production handoff evidence metadata. */
@Component
@Conditional(ProductionEvidenceBackendCondition.Json.class)
public class ProductionEvidenceStore implements ProductionEvidenceRepository {
    private static final Comparator<ProductionEvidence> BY_ID = Comparator.comparing(ProductionEvidence::evidenceId);

    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<ProductionEvidence> current;

    @Autowired
    public ProductionEvidenceStore(ObjectMapper objectMapper,
                                   @Value("${skill-center.production-evidence-storage:./data/governance/production-evidence.json}")
                                   String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    ProductionEvidenceStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.current = load();
    }

    public List<ProductionEvidence> findAll() {
        lock.readLock().lock();
        try {
            return current;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<ProductionEvidence> find(String evidenceId) {
        if (evidenceId == null || evidenceId.isBlank()) return Optional.empty();
        String normalized = ProductionEvidenceCatalog.requireId(evidenceId);
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.evidenceId().equals(normalized)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public ProductionEvidence upsert(ProductionEvidence evidence, int expectedRevision) {
        if (evidence == null) throw new IllegalArgumentException("production evidence is required");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        lock.writeLock().lock();
        try {
            int index = indexOf(evidence.evidenceId());
            int currentRevision = index < 0 ? 0 : current.get(index).revision();
            if (currentRevision != expectedRevision) {
                throw new ProductionEvidenceConflictException("production evidence revision conflict");
            }
            if (evidence.revision() != expectedRevision + 1) {
                throw new ProductionEvidenceConflictException("production evidence revision must advance by one");
            }
            List<ProductionEvidence> next = new ArrayList<>(current);
            if (index < 0) next.add(evidence);
            else next.set(index, evidence);
            List<ProductionEvidence> sorted = sortAndValidate(next);
            persist(sorted);
            current = sorted;
            return evidence;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private int indexOf(String evidenceId) {
        for (int index = 0; index < current.size(); index++) {
            if (current.get(index).evidenceId().equals(evidenceId)) return index;
        }
        return -1;
    }

    private List<ProductionEvidence> load() {
        if (!Files.exists(statePath)) return List.of();
        try {
            List<ProductionEvidence> loaded = objectMapper.readValue(statePath.toFile(), new TypeReference<>() { });
            return sortAndValidate(loaded == null ? List.of() : loaded);
        } catch (IOException | RuntimeException exception) {
            throw new ProductionEvidencePersistenceException("Unable to load production evidence state", exception);
        }
    }

    private List<ProductionEvidence> sortAndValidate(List<ProductionEvidence> values) {
        List<ProductionEvidence> sorted = new ArrayList<>(values == null ? List.of() : values);
        sorted.sort(BY_ID);
        for (int index = 0; index < sorted.size(); index++) {
            ProductionEvidence value = sorted.get(index);
            if (value == null) throw new IllegalArgumentException("production evidence must not be null");
            if (index > 0 && sorted.get(index - 1).evidenceId().equals(value.evidenceId())) {
                throw new IllegalArgumentException("duplicate production evidence id");
            }
        }
        return List.copyOf(sorted);
    }

    private void persist(List<ProductionEvidence> values) {
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
            throw new ProductionEvidencePersistenceException("Unable to persist production evidence state", exception);
        }
    }
}
