package com.huawei.skillcenter.access;

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

@Component
@Conditional(SkillScopeBackendCondition.Json.class)
public class SkillScopeStore implements SkillScopeRepository {
    private static final Comparator<SkillScope> BY_SKILL_ID = Comparator.comparing(SkillScope::skillId);

    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<SkillScope> current;

    @Autowired
    public SkillScopeStore(ObjectMapper objectMapper,
                           @Value("${skill-center.skill-scope-storage:./data/governance/skill-scopes.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    SkillScopeStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.current = load();
    }

    public Optional<SkillScope> find(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return Optional.empty();
        }
        String normalized = SkillScope.normalizeRequiredIdentifier(skillId, "skillId");
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.skillId().equals(normalized)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<SkillScope> findAll() {
        lock.readLock().lock();
        try {
            return current;
        } finally {
            lock.readLock().unlock();
        }
    }

    public SkillScope create(SkillScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        lock.writeLock().lock();
        try {
            if (indexOf(scope.skillId()) >= 0) {
                throw new SkillScopeConflictException("skillId already exists");
            }
            List<SkillScope> next = new ArrayList<>(current);
            next.add(scope);
            List<SkillScope> sorted = sortAndValidate(next);
            persist(sorted);
            current = sorted;
            return scope;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public SkillScope replace(SkillScope scope, int expectedRevision) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        lock.writeLock().lock();
        try {
            int index = indexOf(scope.skillId());
            if (index < 0) {
                throw new SkillScopeConflictException("skill scope does not exist");
            }
            SkillScope existing = current.get(index);
            if (existing.revision() != expectedRevision) {
                throw new SkillScopeConflictException("revision conflict");
            }
            SkillScope updated = new SkillScope(existing.skillId(), scope.visibility(), scope.ownerTeamId(),
                    scope.maintainerUserIds(), existing.revision() + 1,
                    existing.declaredBy(), existing.declaredAt(), scope.updatedBy(), scope.updatedAt());
            List<SkillScope> next = new ArrayList<>(current);
            next.set(index, updated);
            List<SkillScope> sorted = sortAndValidate(next);
            persist(sorted);
            current = sorted;
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private int indexOf(String skillId) {
        for (int index = 0; index < current.size(); index++) {
            if (current.get(index).skillId().equals(skillId)) {
                return index;
            }
        }
        return -1;
    }

    private List<SkillScope> load() {
        if (!Files.exists(statePath)) {
            return List.of();
        }
        try {
            List<SkillScope> loaded = objectMapper.readValue(statePath.toFile(), new TypeReference<>() { });
            return sortAndValidate(loaded == null ? List.of() : loaded);
        } catch (IOException | RuntimeException exception) {
            throw new SkillScopePersistenceException("Unable to load skill scope state", exception);
        }
    }

    private List<SkillScope> sortAndValidate(List<SkillScope> scopes) {
        List<SkillScope> normalized = new ArrayList<>(scopes);
        normalized.sort(BY_SKILL_ID);
        for (int index = 0; index < normalized.size(); index++) {
            SkillScope scope = normalized.get(index);
            if (scope == null) {
                throw new IllegalArgumentException("skill scope must not be null");
            }
            if (index > 0 && normalized.get(index - 1).skillId().equals(scope.skillId())) {
                throw new IllegalArgumentException("duplicate skillId");
            }
        }
        return List.copyOf(normalized);
    }

    private void persist(List<SkillScope> scopes) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), scopes);
            try {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new SkillScopePersistenceException("Unable to persist skill scope state", exception);
        }
    }
}
