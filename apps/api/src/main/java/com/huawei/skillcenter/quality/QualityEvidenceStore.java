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
import java.util.List;

/** JSON quality evidence store with atomic replacement and an in-memory constructor for tests. */
@Component
@Conditional(QualityEvidenceBackendCondition.Json.class)
public class QualityEvidenceStore implements QualityEvidenceRepository {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final boolean persistent;
    private QualityEvidenceState current;

    public QualityEvidenceStore() { this(null, new ObjectMapper().findAndRegisterModules(), false); }

    @Autowired
    public QualityEvidenceStore(ObjectMapper objectMapper,
            @Value("${skill-center.quality-evidence-storage:./data/governance/quality-evidence.json}") String statePath) {
        this(Path.of(statePath), objectMapper, true);
    }

    public QualityEvidenceStore(Path statePath, ObjectMapper objectMapper) { this(statePath, objectMapper, true); }

    private QualityEvidenceStore(Path statePath, ObjectMapper objectMapper, boolean persistent) {
        this.statePath = statePath == null ? null : statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.persistent = persistent;
        this.current = load();
    }

    @Override
    public synchronized QualityEvidenceState load() {
        if (!persistent || statePath == null || !Files.exists(statePath)) return current == null ? empty() : current;
        try {
            QualityEvidenceState restored = objectMapper.readValue(statePath.toFile(), new TypeReference<>() { });
            restored = restored == null ? empty() : restored;
            QualityEvidenceStateValidator.validate(restored);
            current = restored;
            return current;
        } catch (IOException | RuntimeException exception) {
            throw new QualityEvidencePersistenceException(exception);
        }
    }

    @Override
    public synchronized void save(QualityEvidenceState state) {
        if (state == null) throw new IllegalArgumentException("quality evidence state is required");
        try { QualityEvidenceStateValidator.validate(state); }
        catch (IllegalArgumentException exception) { throw new QualityEvidencePersistenceException(exception); }
        if (persistent && statePath != null) {
            try {
                Path parent = statePath.getParent();
                if (parent != null) Files.createDirectories(parent);
                Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
                try { Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException exception) { throw new QualityEvidencePersistenceException(exception); }
        }
        current = state;
    }

    @Override public synchronized QualityEvidenceState update(StateUpdate update) {
        if (update == null) throw new IllegalArgumentException("quality evidence update is required");
        QualityEvidenceState next = update.apply(load()); save(next); return current;
    }
    @Override public synchronized void clear() { save(empty()); }
    private QualityEvidenceState empty() {
        QualityEvidenceState empty = new QualityEvidenceState(List.of(), null, List.of(), List.of());
        QualityEvidenceStateValidator.validate(empty);
        return empty;
    }

    public static class QualityEvidencePersistenceException extends RuntimeException {
        public QualityEvidencePersistenceException(Throwable cause) {
            super("quality evidence persistence failed", cause);
        }
    }
}
