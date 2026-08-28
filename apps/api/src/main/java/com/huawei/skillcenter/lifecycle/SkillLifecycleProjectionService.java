package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillNotVisibleException;
import com.huawei.skillcenter.access.SkillVisibilityContext;
import com.huawei.skillcenter.governance.Actor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class SkillLifecycleProjectionService {
    static final int MAX_IMPACT_DEPTH = 8;
    static final int MAX_IMPACT_NODES = 100;

    private final SkillLifecycleProjectionSource source;
    private final SkillLifecycleProjectionRepository repository;
    private final SkillAuthorizationService authorizationService;
    private final Clock clock;
    private final SkillLifecycleProjectionFreshnessPolicy freshnessPolicy;

    public SkillLifecycleProjectionService(SkillLifecycleProjectionSource source,
                                           SkillLifecycleProjectionRepository repository,
                                           SkillAuthorizationService authorizationService,
                                           Clock clock) {
        this(source, repository, authorizationService, clock,
                SkillLifecycleProjectionFreshnessPolicy.defaults());
    }

    public SkillLifecycleProjectionService(SkillLifecycleProjectionSource source,
                                           SkillLifecycleProjectionRepository repository,
                                           SkillAuthorizationService authorizationService,
                                           Clock clock,
                                           SkillLifecycleProjectionFreshnessPolicy freshnessPolicy) {
        this.source = require(source, "source");
        this.repository = require(repository, "repository");
        this.authorizationService = require(authorizationService, "authorizationService");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.freshnessPolicy = freshnessPolicy == null
                ? SkillLifecycleProjectionFreshnessPolicy.defaults() : freshnessPolicy;
    }

    public SkillLifecycleProjectionStatus status() {
        return repository.status();
    }

    public SkillLifecycleProjectionPreflight preflight() {
        SkillLifecycleProjectionSnapshot snapshot = snapshot();
        SkillLifecycleProjectionStatus status = repository.status();
        boolean matches = status.sourceSha256().equals(snapshot.sourceSha256());
        return new SkillLifecycleProjectionPreflight(
                status.backend(),
                status.revision(),
                status.sourceSha256(),
                snapshot.sourceSha256(),
                snapshot.sourceGeneratedAt(),
                snapshot.skills().size(),
                snapshot.versions().size(),
                snapshot.releases().size(),
                snapshot.scopes().size(),
                snapshot.relations().size(),
                matches,
                matches ? "" : "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED");
    }

    public SkillLifecycleProjectionReconciliation reconciliation() {
        SkillLifecycleProjectionSnapshot snapshot = snapshot();
        SkillLifecycleProjectionStatus status = repository.status();
        SkillLifecycleProjectionCounts sourceCounts = counts(snapshot);
        SkillLifecycleProjectionCounts projectedCounts = new SkillLifecycleProjectionCounts(
                status.skillCount(), status.versionCount(), status.releaseCount(),
                status.scopeCount(), status.relationCount());
        SkillLifecycleProjectionCountDelta countDelta = sourceCounts.subtract(projectedCounts);
        String projectedHash = status.sourceSha256();
        boolean sourceChanged = !projectedHash.equals(snapshot.sourceSha256());
        boolean countMismatch = countDelta.skillCount() != 0
                || countDelta.versionCount() != 0
                || countDelta.releaseCount() != 0
                || countDelta.scopeCount() != 0
                || countDelta.relationCount() != 0;
        Optional<Instant> importedAt = repository.importedAt();
        Instant observedAt = clock.instant();
        Long ageSeconds = importedAt.map(value -> ageSeconds(value, observedAt)).orElse(null);

        String state;
        String reasonCode;
        if ("FAIL_CLOSED".equals(status.state())) {
            state = "NOT_READY";
            reasonCode = "SKILL_LIFECYCLE_PROJECTION_NOT_READY";
        } else if ("json".equals(status.backend()) && importedAt.isEmpty()) {
            state = "LIVE_SOURCE";
            reasonCode = "";
        } else if (status.revision() == 0L && importedAt.isEmpty()) {
            state = "NOT_IMPORTED";
            reasonCode = "SKILL_LIFECYCLE_PROJECTION_NOT_IMPORTED";
        } else if (sourceChanged) {
            state = "DRIFTED";
            reasonCode = "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED";
        } else if (countMismatch) {
            state = "DRIFTED";
            reasonCode = "SKILL_LIFECYCLE_PROJECTION_COUNT_MISMATCH";
        } else if (importedAt.isEmpty()) {
            state = "NOT_IMPORTED";
            reasonCode = "SKILL_LIFECYCLE_PROJECTION_NOT_IMPORTED";
        } else if (ageSeconds != null && ageSeconds > freshnessPolicy.maxProjectionAgeSeconds()) {
            state = "STALE";
            reasonCode = "SKILL_LIFECYCLE_PROJECTION_SOURCE_STALE";
        } else {
            state = "HEALTHY";
            reasonCode = "";
        }
        return new SkillLifecycleProjectionReconciliation(
                status.backend(), state, reasonCode, status.schemaVersion(), status.revision(),
                projectedHash, snapshot.sourceSha256(), snapshot.sourceGeneratedAt(), importedAt.orElse(null),
                observedAt, ageSeconds, freshnessPolicy.maxProjectionAgeSeconds(),
                sourceCounts, projectedCounts, countDelta);
    }

    public SkillLifecycleProjectionImportResult importSnapshot(String expectedSourceSha256, Actor actor, String requestId) {
        require(actor, "actor");
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        SkillLifecycleProjectionSnapshot snapshot = snapshot();
        SkillLifecycleProjectionStatus status = repository.status();
        if (!snapshot.sourceSha256().equals(normalizeSha256(expectedSourceSha256))) {
            return new SkillLifecycleProjectionImportResult(
                    false, false, status.revision(), snapshot.sourceSha256(),
                    snapshot.skills().size(), snapshot.versions().size(), snapshot.releases().size(),
                    snapshot.scopes().size(), snapshot.relations().size(),
                    "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED");
        }
        return repository.replace(snapshot);
    }

    public List<SkillLifecycleProjectionView> findSkills(SkillLifecycleProjectionQuery query, Actor actor) {
        require(actor, "actor");
        SkillLifecycleProjectionQuery resolved = query == null ? SkillLifecycleProjectionQuery.defaults() : query;
        if (!resolved.skillId().isBlank()) {
            authorizationService.requireVisible(resolved.skillId(), actor, SkillVisibilityContext.GOVERNANCE);
        }
        List<SkillLifecycleProjectionView> rows = repository.findSkills(resolved).stream()
                .filter(view -> isVisible(view.skillId(), actor))
                .map(view -> sanitizeView(view, scopeReadable(view.skillId(), actor)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        rows.sort(Comparator.comparing(SkillLifecycleProjectionView::skillId)
                .thenComparing(SkillLifecycleProjectionView::version));
        return paginate(rows, resolved.page(), resolved.pageSize());
    }

    public SkillLifecycleImpactView findImpact(String skillId, String version, Actor actor) {
        require(actor, "actor");
        String normalizedSkillId = skillId == null ? "" : skillId.trim();
        String normalizedVersion = version == null ? "" : version.trim();
        authorizationService.requireVisible(normalizedSkillId, actor, SkillVisibilityContext.GOVERNANCE);
        SkillLifecycleImpactView raw = repository.findImpact(normalizedSkillId, normalizedVersion);
        List<SkillLifecycleImpactView.Node> nodes = raw.nodes().stream()
                .filter(node -> node.depth() <= MAX_IMPACT_DEPTH)
                .filter(node -> isVisible(node.skillId(), actor))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        nodes.sort(Comparator.comparingInt(SkillLifecycleImpactView.Node::depth)
                .thenComparing(SkillLifecycleImpactView.Node::skillId)
                .thenComparing(SkillLifecycleImpactView.Node::version)
                .thenComparing(SkillLifecycleImpactView.Node::relationId));
        boolean truncated = raw.truncated()
                || raw.nodes().stream().anyMatch(node -> node.depth() > MAX_IMPACT_DEPTH)
                || nodes.size() > MAX_IMPACT_NODES;
        if (nodes.size() > MAX_IMPACT_NODES) {
            nodes = new ArrayList<>(nodes.subList(0, MAX_IMPACT_NODES));
        }
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

    private static SkillLifecycleProjectionCounts counts(SkillLifecycleProjectionSnapshot snapshot) {
        return new SkillLifecycleProjectionCounts(
                snapshot.skills().size(), snapshot.versions().size(), snapshot.releases().size(),
                snapshot.scopes().size(), snapshot.relations().size());
    }

    private static long ageSeconds(Instant importedAt, Instant observedAt) {
        if (!observedAt.isAfter(importedAt)) return 0L;
        return Math.max(0L, Duration.between(importedAt, observedAt).getSeconds());
    }

    private SkillLifecycleProjectionView sanitizeView(SkillLifecycleProjectionView view, boolean scopeReadable) {
        return new SkillLifecycleProjectionView(
                view.skillId(),
                view.version(),
                view.packageId(),
                view.status(),
                view.latestVersion(),
                view.latestStatus(),
                view.versionCount(),
                view.publishedVersionCount(),
                view.activeReleaseCount(),
                scopeReadable ? view.visibility() : "",
                scopeReadable ? view.ownerTeamId() : "",
                view.releases());
    }

    private boolean scopeReadable(String skillId, Actor actor) {
        try {
            authorizationService.requireScopeReadable(skillId, actor);
            return true;
        } catch (SkillNotVisibleException exception) {
            return false;
        }
    }

    private boolean isVisible(String skillId, Actor actor) {
        try {
            authorizationService.requireVisible(skillId, actor, SkillVisibilityContext.GOVERNANCE);
            return true;
        } catch (SkillNotVisibleException exception) {
            return false;
        }
    }

    private static List<SkillLifecycleProjectionView> paginate(List<SkillLifecycleProjectionView> rows,
                                                               int page,
                                                               int pageSize) {
        int from = Math.min((page - 1) * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        return List.copyOf(rows.subList(from, to));
    }

    private static String normalizeSha256(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
