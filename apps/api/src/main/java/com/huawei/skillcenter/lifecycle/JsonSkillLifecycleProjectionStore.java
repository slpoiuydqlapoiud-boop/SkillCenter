package com.huawei.skillcenter.lifecycle;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class JsonSkillLifecycleProjectionStore implements SkillLifecycleProjectionRepository {
    private static final String BACKEND = "json";
    private static final int MAX_IMPACT_DEPTH = 8;
    private static final int MAX_IMPACT_NODES = 100;

    private final SkillLifecycleProjectionSource source;
    private final Clock clock;

    public JsonSkillLifecycleProjectionStore(SkillLifecycleProjectionSource source, Clock clock) {
        this.source = source;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public SkillLifecycleProjectionStatus status() {
        try {
            SkillLifecycleProjectionSnapshot snapshot = snapshot();
            return SkillLifecycleProjectionStatus.ready(
                    BACKEND,
                    null,
                    0L,
                    snapshot.sourceSha256(),
                    snapshot.skills().size(),
                    snapshot.versions().size(),
                    snapshot.releases().size(),
                    snapshot.scopes().size(),
                    snapshot.relations().size());
        } catch (RuntimeException exception) {
            return SkillLifecycleProjectionStatus.failClosed(
                    BACKEND, null, 0L, "0".repeat(64), 0, 0, 0, 0, 0,
                    "SKILL_LIFECYCLE_PROJECTION_NOT_READY");
        }
    }

    @Override
    public SkillLifecycleProjectionImportResult replace(SkillLifecycleProjectionSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is required");
        }
        SkillLifecycleProjectionSnapshot current = snapshot();
        if (!current.sourceSha256().equals(snapshot.sourceSha256())) {
            return new SkillLifecycleProjectionImportResult(
                    false, false, 0L, current.sourceSha256(),
                    current.skills().size(), current.versions().size(), current.releases().size(),
                    current.scopes().size(), current.relations().size(),
                    "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED");
        }
        return new SkillLifecycleProjectionImportResult(
                false, true, 0L, current.sourceSha256(),
                current.skills().size(), current.versions().size(), current.releases().size(),
                current.scopes().size(), current.relations().size(), "");
    }

    @Override
    public List<SkillLifecycleProjectionView> findSkills(SkillLifecycleProjectionQuery query) {
        SkillLifecycleProjectionInput input = source.read();
        SkillLifecycleProjectionQuery resolved = query == null ? SkillLifecycleProjectionQuery.defaults() : query;
        Map<String, SkillLifecycleSkillRow> skillsById = new HashMap<>();
        input.skills().forEach(skill -> skillsById.put(skill.skillId(), skill));
        Map<String, List<SkillLifecycleReleaseRow>> releasesByVersion = new HashMap<>();
        input.releases().forEach(release -> releasesByVersion
                .computeIfAbsent(versionKey(release.skillId(), release.version()), ignored -> new ArrayList<>())
                .add(release));

        List<SkillLifecycleProjectionView> rows = new ArrayList<>();
        for (SkillLifecycleVersionRow version : input.versions()) {
            if (!resolved.skillId().isBlank() && !resolved.skillId().equals(version.skillId())) {
                continue;
            }
            if (!resolved.version().isBlank() && !resolved.version().equals(version.version())) {
                continue;
            }
            if (!resolved.status().isBlank() && !resolved.status().equals(version.status())) {
                continue;
            }
            List<SkillLifecycleReleaseRow> versionReleases = releasesByVersion.getOrDefault(
                    versionKey(version.skillId(), version.version()), List.of());
            if (resolved.environment() != null && versionReleases.stream()
                    .noneMatch(release -> release.targetEnvironment() == resolved.environment())) {
                continue;
            }
            SkillLifecycleSkillRow skill = skillsById.get(version.skillId());
            rows.add(new SkillLifecycleProjectionView(
                    version.skillId(),
                    version.version(),
                    version.packageId(),
                    version.status(),
                    skill == null ? "" : skill.latestVersion(),
                    skill == null ? "" : skill.latestStatus(),
                    skill == null ? 0 : skill.versionCount(),
                    skill == null ? 0 : skill.publishedVersionCount(),
                    skill == null ? 0 : skill.activeReleaseCount(),
                    skill == null ? "" : skill.visibility().name(),
                    skill == null ? "" : skill.ownerTeamId(),
                    toReleaseViews(versionReleases, resolved.environment())));
        }
        rows.sort(Comparator.comparing(SkillLifecycleProjectionView::skillId)
                .thenComparing(SkillLifecycleProjectionView::version));
        return List.copyOf(rows);
    }

    @Override
    public SkillLifecycleImpactView findImpact(String skillId, String version) {
        SkillLifecycleProjectionInput input = source.read();
        String normalizedSkillId = skillId == null ? "" : skillId.trim();
        String normalizedVersion = version == null ? "" : version.trim();
        SkillLifecycleVersionRow root = input.versions().stream()
                .filter(candidate -> candidate.skillId().equals(normalizedSkillId)
                        && candidate.version().equals(normalizedVersion))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("skill version is required"));

        Map<String, List<SkillLifecycleRelationRow>> reverse = new HashMap<>();
        input.relations().stream()
                .filter(relation -> relation.status() == com.huawei.skillcenter.relationship.SkillRelationStatus.ACTIVE)
                .forEach(relation -> reverse.computeIfAbsent(
                        versionKey(relation.targetSkillId(), relation.targetVersion()), ignored -> new ArrayList<>())
                        .add(relation));
        reverse.values().forEach(values -> values.sort(Comparator.comparing(SkillLifecycleRelationRow::sourceSkillId)
                .thenComparing(SkillLifecycleRelationRow::sourceVersion)
                .thenComparing(SkillLifecycleRelationRow::relationId)));

        Map<String, SkillLifecycleVersionRow> versionsByKey = new HashMap<>();
        input.versions().forEach(item -> versionsByKey.put(versionKey(item.skillId(), item.version()), item));
        Map<String, List<SkillLifecycleReleaseRow>> releasesByVersion = new HashMap<>();
        input.releases().forEach(release -> releasesByVersion
                .computeIfAbsent(versionKey(release.skillId(), release.version()), ignored -> new ArrayList<>())
                .add(release));

        Queue<Visit> queue = new ArrayDeque<>();
        String rootKey = versionKey(root.skillId(), root.version());
        queue.add(new Visit(rootKey, 0));
        Set<String> seen = new HashSet<>();
        seen.add(rootKey);
        List<SkillLifecycleImpactView.Node> nodes = new ArrayList<>();
        boolean truncated = false;
        boolean stopTraversal = false;
        while (!queue.isEmpty() && !stopTraversal) {
            Visit current = queue.remove();
            for (SkillLifecycleRelationRow relation : reverse.getOrDefault(current.key(), List.of())) {
                int depth = current.depth() + 1;
                String nextKey = versionKey(relation.sourceSkillId(), relation.sourceVersion());
                if (!seen.add(nextKey)) {
                    continue;
                }
                if (depth > MAX_IMPACT_DEPTH) {
                    truncated = true;
                    continue;
                }
                if (nodes.size() >= MAX_IMPACT_NODES) {
                    truncated = true;
                    stopTraversal = true;
                    break;
                }
                SkillLifecycleVersionRow affected = versionsByKey.get(nextKey);
                if (affected == null) {
                    throw new SkillLifecycleProjectionSourceInvalidException();
                }
                List<SkillLifecycleProjectionView.ReleaseView> safeReleases = toReleaseViews(
                        releasesByVersion.getOrDefault(nextKey, List.of()), null);
                boolean productionPromoted = releasesByVersion.getOrDefault(nextKey, List.of()).stream()
                        .anyMatch(release -> release.targetEnvironment() == com.huawei.skillcenter.release.ReleaseEnvironment.PRODUCTION
                                && release.status() == com.huawei.skillcenter.release.ReleaseStatus.PROMOTED
                                && release.sha256().equals(affected.sha256()));
                nodes.add(new SkillLifecycleImpactView.Node(
                        relation.relationId(),
                        affected.skillId(),
                        affected.version(),
                        relation.relationType().name(),
                        relation.status().name(),
                        depth,
                        productionPromoted,
                        safeReleases));
                queue.add(new Visit(nextKey, depth));
            }
        }
        nodes.sort(Comparator.comparingInt(SkillLifecycleImpactView.Node::depth)
                .thenComparing(SkillLifecycleImpactView.Node::skillId)
                .thenComparing(SkillLifecycleImpactView.Node::version)
                .thenComparing(SkillLifecycleImpactView.Node::relationId));
        return new SkillLifecycleImpactView(normalizedSkillId, normalizedVersion, truncated, nodes);
    }

    private SkillLifecycleProjectionSnapshot snapshot() {
        SkillLifecycleProjectionInput input = source.read();
        return new SkillLifecycleProjectionSnapshot(
                SkillLifecycleProjectionHasher.hash(input),
                clock.instant(),
                input.skills(),
                input.versions(),
                input.releases(),
                input.scopes(),
                input.relations());
    }

    private List<SkillLifecycleProjectionView.ReleaseView> toReleaseViews(List<SkillLifecycleReleaseRow> releases,
                                                                          com.huawei.skillcenter.release.ReleaseEnvironment environmentFilter) {
        return releases.stream()
                .filter(release -> environmentFilter == null || release.targetEnvironment() == environmentFilter)
                .sorted(Comparator.comparing(SkillLifecycleReleaseRow::requestedAt).reversed()
                        .thenComparing(SkillLifecycleReleaseRow::releaseId))
                .map(release -> new SkillLifecycleProjectionView.ReleaseView(
                        release.releaseId(),
                        release.targetEnvironment().name(),
                        release.status().name(),
                        release.gateOutcome()))
                .toList();
    }

    private static String versionKey(String skillId, String version) {
        return skillId + "\u0000" + version;
    }

    private record Visit(String key, int depth) {
    }
}
