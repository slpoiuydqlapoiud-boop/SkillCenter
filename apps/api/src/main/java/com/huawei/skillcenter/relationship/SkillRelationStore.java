package com.huawei.skillcenter.relationship;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@Conditional(SkillRelationBackendCondition.Json.class)
public class SkillRelationStore implements SkillRelationRepository {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<SkillRelation> current;

    @Autowired
    public SkillRelationStore(ObjectMapper objectMapper,
                              @Value("${skill-center.skill-relations-storage:./data/governance/skill-relations.json}") String statePath) {
        this(Path.of(statePath), objectMapper);
    }

    SkillRelationStore(Path statePath, ObjectMapper objectMapper) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.current = load();
    }

    public Optional<SkillRelation> find(String relationId) {
        if (relationId == null || relationId.isBlank()) return Optional.empty();
        String normalized = relationId.trim();
        lock.readLock().lock();
        try {
            return current.stream().filter(value -> value.relationId().equals(normalized)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<SkillRelation> findAll(String sourceSkillId, String sourceVersion,
                                       String targetSkillId, String targetVersion,
                                       SkillRelationStatus status) {
        lock.readLock().lock();
        try {
            return current.stream()
                    .filter(value -> matches(sourceSkillId, value.sourceSkillId()))
                    .filter(value -> matches(sourceVersion, value.sourceVersion()))
                    .filter(value -> matches(targetSkillId, value.targetSkillId()))
                    .filter(value -> matches(targetVersion, value.targetVersion()))
                    .filter(value -> status == null || value.status() == status)
                    .sorted(Comparator.comparing(SkillRelation::declaredAt)
                            .thenComparing(SkillRelation::relationId))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public SkillRelation create(SkillRelation relation) {
        if (relation == null) throw new IllegalArgumentException("relation is required");
        lock.writeLock().lock();
        try {
            ensureNoDuplicate(current, relation);
            List<SkillRelation> next = new ArrayList<>(current);
            next.add(relation);
            validateAcyclic(next);
            persist(next);
            current = List.copyOf(next);
            return relation;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public SkillRelation replace(SkillRelation relation) {
        if (relation == null) throw new IllegalArgumentException("relation is required");
        lock.writeLock().lock();
        try {
            int index = indexOf(relation.relationId());
            if (index < 0) throw new SkillRelationConflictException("relation does not exist");
            SkillRelation existing = current.get(index);
            ensureImmutableContext(existing, relation);
            List<SkillRelation> next = new ArrayList<>(current);
            next.remove(index);
            ensureNoDuplicate(next, relation);
            next.add(relation);
            validateAcyclic(next);
            persist(next);
            current = List.copyOf(next);
            return relation;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private int indexOf(String relationId) {
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).relationId().equals(relationId)) return i;
        }
        return -1;
    }

    private void ensureNoDuplicate(List<SkillRelation> values, SkillRelation relation) {
        if (values.stream().anyMatch(value -> value.relationId().equals(relation.relationId()))) {
            throw new SkillRelationConflictException("relationId already exists");
        }
        if (relation.status() == SkillRelationStatus.ACTIVE && values.stream().anyMatch(value ->
                value.status() == SkillRelationStatus.ACTIVE
                        && value.sourceSkillId().equals(relation.sourceSkillId())
                        && value.sourceVersion().equals(relation.sourceVersion())
                        && value.targetSkillId().equals(relation.targetSkillId())
                        && value.targetVersion().equals(relation.targetVersion())
                        && value.relationType() == relation.relationType())) {
            throw new SkillRelationConflictException("active relation already exists");
        }
    }

    private void ensureImmutableContext(SkillRelation existing, SkillRelation relation) {
        if (!existing.relationId().equals(relation.relationId())
                || !existing.sourceSkillId().equals(relation.sourceSkillId())
                || !existing.sourceVersion().equals(relation.sourceVersion())
                || !existing.targetSkillId().equals(relation.targetSkillId())
                || !existing.targetVersion().equals(relation.targetVersion())
                || existing.relationType() != relation.relationType()
                || !existing.declaredBy().equals(relation.declaredBy())
                || !existing.declaredAt().equals(relation.declaredAt())) {
            throw new SkillRelationConflictException("relation context is immutable");
        }
    }

    private List<SkillRelation> load() {
        if (!Files.exists(statePath)) return List.of();
        try {
            List<SkillRelation> loaded = objectMapper.readValue(statePath.toFile(), new TypeReference<>() { });
            List<SkillRelation> values = new ArrayList<>(loaded == null ? List.of() : loaded);
            List<SkillRelation> checked = new ArrayList<>();
            for (SkillRelation value : values) {
                if (value == null) throw new IllegalArgumentException("relation must not be null");
                ensureNoDuplicate(checked, value);
                checked.add(value);
            }
            validateAcyclic(checked);
            return List.copyOf(checked);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof SkillRelationPersistenceException persistence) throw persistence;
            throw new SkillRelationPersistenceException(exception);
        }
    }

    private void persist(List<SkillRelation> values) {
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
            throw new SkillRelationPersistenceException(exception);
        }
    }

    private void validateAcyclic(List<SkillRelation> values) {
        Map<String, Set<String>> graph = new HashMap<>();
        values.stream().filter(value -> value.status() == SkillRelationStatus.ACTIVE).forEach(value ->
                graph.computeIfAbsent(key(value.sourceSkillId(), value.sourceVersion()), ignored -> new HashSet<>())
                        .add(key(value.targetSkillId(), value.targetVersion())));
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String node : graph.keySet()) {
            if (hasCycle(node, graph, visiting, visited)) {
                throw new SkillRelationConflictException("active relation graph contains a cycle");
            }
        }
    }

    private boolean hasCycle(String node, Map<String, Set<String>> graph,
                             Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) return true;
        if (!visited.add(node)) return false;
        visiting.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (hasCycle(next, graph, visiting, visited)) return true;
        }
        visiting.remove(node);
        return false;
    }

    private static boolean matches(String filter, String value) {
        return filter == null || filter.isBlank() || filter.trim().equals(value);
    }

    static String key(String skillId, String version) {
        return skillId + "\u0000" + version;
    }
}
