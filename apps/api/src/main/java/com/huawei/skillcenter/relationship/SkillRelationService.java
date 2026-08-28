package com.huawei.skillcenter.relationship;

import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillNotVisibleException;
import com.huawei.skillcenter.access.SkillVisibilityContext;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationRecord;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.release.ReleaseEnvironment;
import com.huawei.skillcenter.release.ReleaseRecord;
import com.huawei.skillcenter.release.ReleaseRecordRepository;
import com.huawei.skillcenter.release.ReleaseStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

@Service
public class SkillRelationService {
    private final GovernanceStore governanceStore;
    private final SkillRelationRepository relationStore;
    private final ReleaseRecordRepository releaseStore;
    private final SkillAuthorizationService authorizationService;
    private final Clock clock;

    @Autowired
    public SkillRelationService(GovernanceStore governanceStore, SkillRelationRepository relationStore,
                                ReleaseRecordRepository releaseStore, SkillAuthorizationService authorizationService) {
        this(governanceStore, relationStore, releaseStore, authorizationService, Clock.systemUTC());
    }

    public SkillRelationService(GovernanceStore governanceStore, SkillRelationRepository relationStore,
                                ReleaseRecordRepository releaseStore, Clock clock) {
        this(governanceStore, relationStore, releaseStore, null, clock);
    }

    public SkillRelationService(GovernanceStore governanceStore, SkillRelationRepository relationStore,
                                ReleaseRecordRepository releaseStore, SkillAuthorizationService authorizationService,
                                Clock clock) {
        this.governanceStore = require(governanceStore, "governanceStore");
        this.relationStore = require(relationStore, "relationStore");
        this.releaseStore = require(releaseStore, "releaseStore");
        this.authorizationService = authorizationService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public synchronized SkillRelation create(SkillRelationRequest request, Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("maintainer", "admin"));
        if (request == null || request.relationType() == null) {
            throw new SkillRelationConflictException("relation request is invalid");
        }
        SkillVersion source = findUsableVersion(request.sourceSkillId(), request.sourceVersion());
        SkillVersion target = findUsableVersion(request.targetSkillId(), request.targetVersion());
        if (authorizationService != null) {
            authorizationService.requireManage(source.skillId(), actor);
            authorizationService.requireVisible(target.skillId(), actor, SkillVisibilityContext.GOVERNANCE);
        }
        if (source.skillId().equals(target.skillId()) && source.version().equals(target.version())) {
            throw new SkillRelationConflictException("source and target version must be different");
        }
        List<SkillRelation> active = relationStore.findAll(null, null, null, null, SkillRelationStatus.ACTIVE);
        if (wouldCycle(active, request.sourceSkillId().trim(), request.sourceVersion().trim(),
                request.targetSkillId().trim(), request.targetVersion().trim())) {
            throw new SkillRelationCycleException();
        }
        Instant now = clock.instant();
        SkillRelation relation = SkillRelation.create(
                UUID.randomUUID().toString().replace("-", ""), source.skillId(), source.version(),
                target.skillId(), target.version(), request.relationType(), actor.userId(), now);
        SkillRelation created = relationStore.create(relation);
        audit("SKILL_RELATION_CREATED", created, actor, requestId, "");
        return created;
    }

    public synchronized SkillRelation retire(String relationId, String reason, Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("admin"));
        SkillRelation current = relationStore.find(relationId).orElseThrow(SkillRelationNotFoundException::new);
        if (authorizationService != null) {
            authorizationService.requireManage(current.sourceSkillId(), actor);
        }
        if (current.status() != SkillRelationStatus.ACTIVE) {
            throw new SkillRelationConflictException("relation is already retired");
        }
        SkillRelation retired;
        try {
            retired = current.retire(actor.userId(), reason, clock.instant());
        } catch (IllegalStateException exception) {
            throw new SkillRelationConflictException("relation is already retired");
        }
        SkillRelation result = relationStore.replace(retired);
        audit("SKILL_RELATION_RETIRED", result, actor, requestId, "RELATION_RETIRED");
        return result;
    }

    public List<SkillRelation> list(SkillRelationQuery query, Actor actor) {
        RoleGuard.require(actor, Set.of("maintainer", "reviewer", "admin"));
        SkillRelationQuery resolved = query == null ? SkillRelationQuery.defaults() : query;
        if (authorizationService != null && resolved.sourceSkillId() != null && !resolved.sourceSkillId().isBlank()) {
            authorizationService.requireVisible(resolved.sourceSkillId(), actor, SkillVisibilityContext.GOVERNANCE);
        }
        return relationStore.findAll(resolved.sourceSkillId(), resolved.sourceVersion(), resolved.targetSkillId(),
                resolved.targetVersion(), resolved.status()).stream()
                .filter(relation -> governanceVisible(relation.sourceSkillId(), actor))
                .filter(relation -> governanceVisible(relation.targetSkillId(), actor))
                .toList();
    }

    public SkillRelationImpact impact(String skillId, String version, SkillRelationQuery query, Actor actor) {
        RoleGuard.require(actor, Set.of("maintainer", "reviewer", "admin"));
        findVersion(skillId, version);
        if (authorizationService != null) {
            authorizationService.requireVisible(skillId, actor, SkillVisibilityContext.GOVERNANCE);
        }
        SkillRelationQuery limits = query == null ? SkillRelationQuery.defaults() : query;
        validateLimits(limits);
        List<SkillRelation> active = relationStore.findAll(null, null, null, null, SkillRelationStatus.ACTIVE);
        Map<String, List<SkillRelation>> reverse = new HashMap<>();
        active.forEach(relation -> reverse.computeIfAbsent(
                SkillRelationStore.key(relation.targetSkillId(), relation.targetVersion()), ignored -> new ArrayList<>())
                .add(relation));
        reverse.values().forEach(values -> values.sort(Comparator.comparing(SkillRelation::sourceSkillId)
                .thenComparing(SkillRelation::sourceVersion).thenComparing(SkillRelation::relationId)));

        String root = SkillRelationStore.key(skillId.trim(), version.trim());
        Queue<Visit> queue = new ArrayDeque<>();
        queue.add(new Visit(root, 0, null));
        Set<String> seen = new HashSet<>();
        seen.add(root);
        List<Visit> visits = new ArrayList<>();
        boolean truncated = false;
        while (!queue.isEmpty()) {
            Visit current = queue.remove();
            List<SkillRelation> outgoing = reverse.getOrDefault(current.key(), List.of());
            for (SkillRelation relation : outgoing) {
                if (!governanceVisible(relation.sourceSkillId(), actor)) {
                    continue;
                }
                int depth = current.depth() + 1;
                if (depth > limits.maxDepth()) {
                    truncated = true;
                    continue;
                }
                String next = SkillRelationStore.key(relation.sourceSkillId(), relation.sourceVersion());
                if (!seen.add(next)) continue;
                if (visits.size() >= limits.maxNodes()) {
                    truncated = true;
                    continue;
                }
                Visit visit = new Visit(next, depth, relation);
                visits.add(visit);
                queue.add(visit);
            }
            if (visits.size() >= limits.maxNodes() && !queue.isEmpty()) truncated = true;
        }
        visits.sort(Comparator.comparingInt(Visit::depth)
                .thenComparing(visit -> visit.relation().sourceSkillId())
                .thenComparing(visit -> visit.relation().sourceVersion())
                .thenComparing(visit -> visit.relation().relationId()));
        GovernanceSnapshot snapshot = governanceStore.snapshot();
        List<SkillRelationImpactNode> nodes = visits.stream().map(visit -> {
            SkillVersion affected = findVersion(visit.relation().sourceSkillId(), visit.relation().sourceVersion());
            boolean promoted = releaseStore.findAll(affected.skillId(), affected.version(),
                            ReleaseEnvironment.PRODUCTION, null).stream()
                    .anyMatch(release -> release.status() == ReleaseStatus.PROMOTED
                            && affected.sha256().equals(release.sha256()));
            long installations = snapshot.installations().stream()
                    .filter(item -> affected.skillId().equals(item.skillId())
                            && affected.version().equals(item.version()))
                    .filter(this::activeInstallation)
                    .count();
            return new SkillRelationImpactNode(visit.relation().relationId(), affected.skillId(), affected.version(),
                    visit.relation().relationType(), visit.depth(), affected.status(), promoted, installations);
        }).toList();
        return new SkillRelationImpact(skillId.trim(), version.trim(), limits.maxDepth(), limits.maxNodes(), truncated, nodes);
    }

    private boolean wouldCycle(List<SkillRelation> active, String sourceSkillId, String sourceVersion,
                               String targetSkillId, String targetVersion) {
        Map<String, Set<String>> graph = new HashMap<>();
        active.forEach(relation -> graph.computeIfAbsent(
                SkillRelationStore.key(relation.sourceSkillId(), relation.sourceVersion()), ignored -> new HashSet<>())
                .add(SkillRelationStore.key(relation.targetSkillId(), relation.targetVersion())));
        String source = SkillRelationStore.key(sourceSkillId, sourceVersion);
        String target = SkillRelationStore.key(targetSkillId, targetVersion);
        graph.computeIfAbsent(source, ignored -> new HashSet<>()).add(target);
        return pathExists(target, source, graph, new HashSet<>());
    }

    private boolean pathExists(String current, String target, Map<String, Set<String>> graph, Set<String> visited) {
        if (current.equals(target)) return true;
        if (!visited.add(current)) return false;
        return graph.getOrDefault(current, Set.of()).stream()
                .anyMatch(next -> pathExists(next, target, graph, visited));
    }

    private SkillVersion findUsableVersion(String skillId, String version) {
        SkillVersion candidate = findVersion(skillId, version);
        if ("withdrawn".equalsIgnoreCase(candidate.status())) {
            throw new SkillRelationVersionNotFoundException();
        }
        return candidate;
    }

    private SkillVersion findVersion(String skillId, String version) {
        if (skillId == null || skillId.isBlank() || version == null || version.isBlank()) {
            throw new SkillRelationVersionNotFoundException();
        }
        return governanceStore.snapshot().versions().stream()
                .filter(candidate -> skillId.trim().equals(candidate.skillId())
                        && version.trim().equals(candidate.version()))
                .findFirst()
                .orElseThrow(SkillRelationVersionNotFoundException::new);
    }

    private void validateLimits(SkillRelationQuery query) {
        if (query.maxDepth() < 1 || query.maxDepth() > SkillRelationQuery.MAX_DEPTH
                || query.maxNodes() < 1 || query.maxNodes() > SkillRelationQuery.MAX_NODES) {
            throw new SkillRelationLimitException();
        }
    }

    private boolean activeInstallation(InstallationRecord item) {
        return "installed".equalsIgnoreCase(item.status()) || "installing".equalsIgnoreCase(item.status());
    }

    private boolean governanceVisible(String skillId, Actor actor) {
        if (authorizationService == null) {
            return true;
        }
        try {
            authorizationService.requireVisible(skillId, actor, SkillVisibilityContext.GOVERNANCE);
            return true;
        } catch (SkillNotVisibleException hidden) {
            return false;
        }
    }

    private void audit(String action, SkillRelation relation, Actor actor, String requestId, String reasonCode) {
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(), action, "SKILL_RELATION",
                relation.relationId(), actor.userId(), actor.role(), requestId, clock.instant(), Map.of(
                        "relationId", relation.relationId(), "sourceSkillId", relation.sourceSkillId(),
                        "sourceVersion", relation.sourceVersion(), "targetSkillId", relation.targetSkillId(),
                        "targetVersion", relation.targetVersion(), "relationType", relation.relationType().name(),
                        "status", relation.status().name(), "reasonCode", reasonCode)));
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record Visit(String key, int depth, SkillRelation relation) {
    }
}
