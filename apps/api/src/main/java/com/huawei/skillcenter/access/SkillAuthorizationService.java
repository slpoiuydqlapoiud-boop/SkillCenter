package com.huawei.skillcenter.access;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceConfiguration;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleBinding;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.governance.TeamDefinition;
import com.huawei.skillcenter.governance.OrganizationDirectorySyncService;
import com.huawei.skillcenter.search.SkillSearchRefreshEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class SkillAuthorizationService {
    private static final Set<String> ACTIVE_STATUSES = Set.of("active");
    private static final Set<String> CATALOG_VISIBLE_STATUSES = Set.of("published", "deprecated");

    private final SkillScopeRepository scopeStore;
    private final GovernanceStore governanceStore;
    private final Clock clock;
    private final OrganizationDirectorySyncService organizationDirectory;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public SkillAuthorizationService(SkillScopeRepository scopeStore, GovernanceStore governanceStore,
                                     OrganizationDirectorySyncService organizationDirectory,
                                     ApplicationEventPublisher eventPublisher) {
        this(scopeStore, governanceStore, Clock.systemUTC(), organizationDirectory, eventPublisher);
    }

    public SkillAuthorizationService(SkillScopeRepository scopeStore, GovernanceStore governanceStore) {
        this(scopeStore, governanceStore, Clock.systemUTC(), null, event -> { });
    }

    SkillAuthorizationService(SkillScopeRepository scopeStore, GovernanceStore governanceStore, Clock clock) {
        this(scopeStore, governanceStore, clock, null, event -> { });
    }

    public SkillAuthorizationService(SkillScopeRepository scopeStore, GovernanceStore governanceStore, Clock clock,
                                      OrganizationDirectorySyncService organizationDirectory) {
        this(scopeStore, governanceStore, clock, organizationDirectory, event -> { });
    }

    public SkillAuthorizationService(SkillScopeRepository scopeStore, GovernanceStore governanceStore, Clock clock,
                                     OrganizationDirectorySyncService organizationDirectory,
                                     ApplicationEventPublisher eventPublisher) {
        this.scopeStore = scopeStore;
        this.governanceStore = governanceStore;
        this.clock = clock;
        this.organizationDirectory = organizationDirectory;
        this.eventPublisher = eventPublisher == null ? event -> { } : eventPublisher;
    }

    public SkillScope effectiveScope(String skillId) {
        String normalizedSkillId = normalizeSkillId(skillId);
        Optional<SkillScope> explicit = scopeStore.find(normalizedSkillId);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        List<SkillVersion> versions = versionsForSkill(normalizedSkillId);
        if (versions.isEmpty()) {
            throw new SkillScopeNotFoundException();
        }
        Optional<SkillVersion> latestNonWithdrawn = latestNonWithdrawnVersion(normalizedSkillId);
        SkillVersion shapeVersion = latestVersion(normalizedSkillId).orElseThrow(SkillScopeNotFoundException::new);
        Instant now = shapeVersion.uploadedAt() == null ? Instant.now(clock) : shapeVersion.uploadedAt();
        return new SkillScope(normalizedSkillId, SkillVisibility.PUBLIC, "",
                latestNonWithdrawn.stream()
                        .map(SkillVersion::uploadedBy)
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .findFirst()
                        .stream()
                        .toList(),
                1, "system", now, "system", now);
    }

    public void requireVisible(String skillId, Actor actor, SkillVisibilityContext context) {
        if (isAdmin(actor)) {
            return;
        }
        SkillScope scope;
        try {
            scope = effectiveScope(skillId);
        } catch (SkillScopeNotFoundException exception) {
            throw new SkillNotVisibleException();
        }
        if (!hasVisibleVersion(scope.skillId(), context, actor)) {
            throw new SkillNotVisibleException();
        }
        if (context == SkillVisibilityContext.GOVERNANCE && isReviewer(actor)) {
            return;
        }
        if (!canSeeScope(scope, actor, governanceStore.snapshot().configuration())) {
            throw new SkillNotVisibleException();
        }
    }

    public void requireManage(String skillId, Actor actor) {
        if (isAdmin(actor)) {
            return;
        }
        SkillScope scope;
        try {
            scope = effectiveScope(skillId);
        } catch (SkillScopeNotFoundException exception) {
            throw new SkillManageForbiddenException();
        }
        if (!canManageScope(scope, actor, governanceStore.snapshot().configuration())) {
            throw new SkillManageForbiddenException();
        }
    }

    /**
     * Scope metadata is governance data, not ordinary Skill content. Only
     * administrators, reviewers, or actual Skill maintainers may read it.
     */
    public void requireScopeReadable(String skillId, Actor actor) {
        if (isAdmin(actor) || isReviewer(actor)) {
            return;
        }
        SkillScope scope;
        try {
            scope = effectiveScope(skillId);
        } catch (SkillScopeNotFoundException exception) {
            throw new SkillNotVisibleException();
        }
        if (!canManageScope(scope, actor, governanceStore.snapshot().configuration())) {
            throw new SkillNotVisibleException();
        }
    }

    public void requireSubmitVersion(String skillId, Actor actor) {
        String normalizedSkillId = normalizeSkillId(skillId);
        if (isAdmin(actor)) {
            return;
        }
        if (!hasDeclaredScope(normalizedSkillId) && versionsForSkill(normalizedSkillId).isEmpty()) {
            if (RoleGuard.isDeveloper(actor)) {
                return;
            }
            throw new SkillManageForbiddenException();
        }
        requireManage(skillId, actor);
    }

    public List<String> visibleSkillIds(Actor actor) {
        TreeSet<String> visible = new TreeSet<>();
        governanceStore.snapshot().versions().stream()
                .filter(version -> CATALOG_VISIBLE_STATUSES.contains(normalize(version.status())))
                .map(SkillVersion::skillId)
                .filter(skillId -> skillId != null && !skillId.isBlank())
                .distinct()
                .forEach(skillId -> {
                    try {
                        requireVisible(skillId, actor, SkillVisibilityContext.CATALOG);
                        visible.add(skillId.trim());
                    } catch (RuntimeException ignored) {
                        // hidden or unmanaged skills are intentionally filtered from the catalog view
                    }
                });
        return List.copyOf(visible);
    }

    public SkillScope updateScope(String skillId, SkillScopeMutation mutation, Actor actor, String requestId) {
        if (!isAdmin(actor)) {
            throw new SkillManageForbiddenException();
        }
        String normalizedSkillId = normalizeSkillId(skillId);
        SkillScopeMutation safeMutation = validateMutation(mutation);
        validateOwnership(safeMutation, governanceStore.snapshot().configuration());
        Instant now = Instant.now(clock);
        Optional<SkillScope> existing = scopeStore.find(normalizedSkillId);
        SkillScope updated;
        if (existing.isPresent()) {
            SkillScope replacement = safeMutation.toScope(normalizedSkillId, existing.get().revision(), now, now);
            updated = scopeStore.replace(replacement, safeMutation.revision());
        } else {
            if (safeMutation.revision() != 0) {
                throw new SkillScopeConflictException("skill scope does not exist");
            }
            SkillScope created = safeMutation.toScope(normalizedSkillId, 1, now, now);
            updated = scopeStore.create(created);
        }
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(),
                existing.isPresent() ? "SKILL_SCOPE_UPDATED" : "SKILL_SCOPE_CREATED",
                "SKILL_SCOPE", updated.skillId(), actor.userId(), actor.role(), requestId, now,
                Map.of(
                        "visibility", updated.visibility().name(),
                        "ownerTeamId", updated.ownerTeamId(),
                        "maintainerCount", Integer.toString(updated.maintainerUserIds().size()),
                        "revision", Integer.toString(updated.revision())
                )));
        eventPublisher.publishEvent(new SkillSearchRefreshEvent(updated.skillId(), updated.revision(), "SKILL_SCOPE_SAVED"));
        return updated;
    }

    boolean hasDeclaredScope(String skillId) {
        return scopeStore.find(normalizeSkillId(skillId)).isPresent();
    }

    private SkillScopeMutation validateMutation(SkillScopeMutation mutation) {
        try {
            if (mutation == null) {
                throw new IllegalArgumentException("mutation is required");
            }
            return mutation;
        } catch (IllegalArgumentException exception) {
            throw new SkillScopeInvalidException(exception);
        }
    }

    private void validateOwnership(SkillScopeMutation mutation, GovernanceConfiguration configuration) {
        if (mutation.visibility() == SkillVisibility.TEAM && !hasActiveTeam(configuration, mutation.ownerTeamId())) {
            throw new SkillScopeInvalidException();
        }
        if (mutation.visibility() == SkillVisibility.RESTRICTED && mutation.maintainerUserIds().isEmpty()) {
            throw new SkillScopeInvalidException();
        }
    }

    private boolean canSeeScope(SkillScope scope, Actor actor, GovernanceConfiguration configuration) {
        return switch (scope.visibility()) {
            case PUBLIC -> true;
            case TEAM -> belongsToActiveTeam(actor, scope.ownerTeamId(), configuration);
            case RESTRICTED -> scope.maintainerUserIds().contains(actor.userId());
        };
    }

    private boolean canManageScope(SkillScope scope, Actor actor, GovernanceConfiguration configuration) {
        if (!RoleGuard.isDeveloper(actor)) {
            return false;
        }
        return switch (scope.visibility()) {
            case PUBLIC -> scope.maintainerUserIds().contains(actor.userId());
            case TEAM -> scope.maintainerUserIds().contains(actor.userId())
                    || (hasActiveMaintainerBinding(actor, scope.ownerTeamId(), configuration)
                    && belongsToActiveTeam(actor, scope.ownerTeamId(), configuration));
            case RESTRICTED -> scope.maintainerUserIds().contains(actor.userId());
        };
    }

    private boolean belongsToActiveTeam(Actor actor, String teamId, GovernanceConfiguration configuration) {
        if (actor == null || teamId == null || teamId.isBlank()) {
            return false;
        }
        Optional<TeamDefinition> activeTeam = configuration.teams().stream()
                .filter(team -> teamId.equals(team.teamId()))
                .filter(team -> ACTIVE_STATUSES.contains(normalize(team.status())))
                .findFirst();
        if (activeTeam.isEmpty()) {
            return false;
        }
        if (organizationDirectory != null && !organizationDirectory.isLocalMode()
                && !organizationDirectory.allows(actor, teamId)) {
            return false;
        }
        if (actor.teamClaimsAuthoritative()) {
            return actor.teamIds().contains(teamId);
        }
        if (!activeTeam.get().memberUserIds().contains(actor.userId())) {
            return false;
        }
        return configuration.roleBindings().stream()
                .filter(binding -> actor.userId().equals(binding.userId()))
                .filter(binding -> teamId.equals(binding.teamId()))
                .anyMatch(binding -> ACTIVE_STATUSES.contains(normalize(binding.status())));
    }

    private boolean hasActiveMaintainerBinding(Actor actor, String teamId, GovernanceConfiguration configuration) {
        if (teamId == null || teamId.isBlank()) {
            return false;
        }
        return configuration.roleBindings().stream()
                .filter(binding -> actor.userId().equals(binding.userId()))
                .filter(binding -> teamId.equals(binding.teamId()))
                .filter(binding -> "maintainer".equals(normalize(binding.role())))
                .anyMatch(binding -> ACTIVE_STATUSES.contains(normalize(binding.status())));
    }

    private boolean hasActiveTeam(GovernanceConfiguration configuration, String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return false;
        }
        return configuration.teams().stream()
                .anyMatch(team -> teamId.equals(team.teamId()) && ACTIVE_STATUSES.contains(normalize(team.status())));
    }

    private boolean hasVisibleVersion(String skillId, SkillVisibilityContext context, Actor actor) {
        List<SkillVersion> versions = versionsForSkill(skillId);
        if (versions.isEmpty()) {
            return hasDeclaredScope(skillId) && context == SkillVisibilityContext.GOVERNANCE
                    && (isAdmin(actor) || isReviewer(actor));
        }
        if (context == SkillVisibilityContext.GOVERNANCE) {
            return true;
        }
        return versions.stream().anyMatch(version -> CATALOG_VISIBLE_STATUSES.contains(normalize(version.status())));
    }

    private Optional<SkillVersion> latestNonWithdrawnVersion(String skillId) {
        return versionsForSkill(skillId).stream()
                .filter(version -> !"withdrawn".equals(normalize(version.status())))
                .max(Comparator.comparing(SkillVersion::uploadedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private Optional<SkillVersion> latestVersion(String skillId) {
        return versionsForSkill(skillId).stream()
                .max(Comparator.comparing(SkillVersion::uploadedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private List<SkillVersion> versionsForSkill(String skillId) {
        return governanceStore.snapshot().versions().stream()
                .filter(version -> skillId.equals(version.skillId()))
                .toList();
    }

    private String normalizeSkillId(String skillId) {
        try {
            return SkillScope.normalizeRequiredIdentifier(skillId, "skillId");
        } catch (IllegalArgumentException exception) {
            throw new SkillScopeInvalidException(exception);
        }
    }

    private boolean isAdmin(Actor actor) {
        return actor != null && "admin".equals(actor.role());
    }

    private boolean isReviewer(Actor actor) {
        return actor != null && "reviewer".equals(actor.role());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
