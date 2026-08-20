package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.skill.SkillCatalogService;
import com.huawei.skillcenter.skill.SkillNotFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GovernanceConfigurationService {
    private static final Set<String> ROLES = Set.of("viewer", "maintainer", "reviewer", "admin");
    private static final Set<String> VISIBILITIES = Set.of("public", "team");
    private static final Set<String> ACTIVE_STATUSES = Set.of("active");
    private static final String INACTIVE = "inactive";

    private final GovernanceStore store;
    private final SkillCatalogService catalogService;

    public GovernanceConfigurationService(GovernanceStore store) {
        this(store, null);
    }

    @Autowired
    public GovernanceConfigurationService(GovernanceStore store, SkillCatalogService catalogService) {
        this.store = store;
        this.catalogService = catalogService;
    }

    public GovernanceConfigurationView read(Actor actor) {
        RoleGuard.require(actor, ROLES);
        GovernanceConfiguration configuration = store.snapshot().configuration();
        List<TeamDefinition> teams = active(configuration.teams());
        List<RoleBinding> bindings = active(configuration.roleBindings());
        List<TeamDefinition> visibleTeams = teams;
        List<RoleBinding> visibleBindings = bindings;
        List<CategoryDefinition> categories = active(configuration.categories());
        List<TagDefinition> tags = active(configuration.tags());
        List<CollectionDefinition> collections = active(configuration.collections()).stream()
                .filter(collection -> visible(collection, actor, visibleBindings, visibleTeams))
                .toList();
        if (!Set.of("admin", "reviewer").contains(actor.role())) {
            teams = teams.stream().filter(team -> team.memberUserIds().contains(actor.userId())).toList();
            bindings = List.of();
        }
        return new GovernanceConfigurationView(teams, bindings, categories, tags, collections,
                configuration.platformPolicy());
    }

    public GovernanceConfigurationView readAdmin(Actor actor) {
        requireAdmin(actor);
        GovernanceConfiguration configuration = store.snapshot().configuration();
        return new GovernanceConfigurationView(configuration.teams(), configuration.roleBindings(),
                configuration.categories(), configuration.tags(), configuration.collections(), configuration.platformPolicy());
    }

    public TeamDefinition upsertTeam(TeamMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null) {
            throw invalid("Team payload is required");
        }
        String teamId = stableId(request.teamId(), "teamId");
        String name = text(request.name(), "name");
        String owner = optionalText(request.ownerUserId());
        List<String> members = uniqueTexts(request.memberUserIds(), "memberUserIds");
        Instant now = Instant.now();
        GovernanceConfiguration current = store.snapshot().configuration();
        TeamDefinition existing = find(current.teams(), teamId, TeamDefinition::teamId);
        TeamDefinition team = new TeamDefinition(teamId, name, optionalText(request.description()), owner, members,
                "active", existing == null || existing.createdAt() == null ? now : existing.createdAt(), now);
        List<TeamDefinition> teams = replace(current.teams(), team, TeamDefinition::teamId);
        GovernanceConfiguration candidate = new GovernanceConfiguration(teams, current.roleBindings(), current.categories(),
                current.tags(), current.collections(), current.platformPolicy());
        validateConfiguration(candidate);
        save(candidate, existing == null ? "TEAM_CREATED" : "TEAM_UPDATED", "TEAM", teamId, actor, requestId,
                Map.of("name", team.name()));
        return team;
    }

    public TeamDefinition createTeam(TeamMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request != null && find(store.snapshot().configuration().teams(), request.teamId(), TeamDefinition::teamId) != null) {
            throw new ConfigConflictException("Duplicate teamId: " + request.teamId());
        }
        return upsertTeam(request, actor, requestId);
    }

    public void deactivateTeam(String teamId, Actor actor, String requestId) {
        requireAdmin(actor);
        GovernanceConfiguration current = store.snapshot().configuration();
        TeamDefinition existing = find(current.teams(), stableId(teamId, "teamId"), TeamDefinition::teamId);
        if (existing == null || INACTIVE.equals(existing.status())) {
            return;
        }
        Instant now = Instant.now();
        TeamDefinition inactive = new TeamDefinition(existing.teamId(), existing.name(), existing.description(),
                existing.ownerUserId(), existing.memberUserIds(), INACTIVE, existing.createdAt(), now);
        GovernanceConfiguration candidate = withTeams(current, replace(current.teams(), inactive, TeamDefinition::teamId));
        validateConfiguration(candidate);
        save(candidate, "TEAM_DEACTIVATED", "TEAM", inactive.teamId(), actor, requestId, Map.of());
    }

    public RoleBinding upsertRoleBinding(String userId, RoleBindingMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        String resolvedUserId = id(userId, "userId");
        if (request == null) {
            throw invalid("Role binding payload is required");
        }
        String role = normalize(request.role());
        if (!ROLES.contains(role)) {
            throw invalid("role must be viewer, maintainer, reviewer, or admin");
        }
        String teamId = optionalText(request.teamId());
        GovernanceConfiguration current = store.snapshot().configuration();
        Instant now = Instant.now();
        RoleBinding existing = find(current.roleBindings(), resolvedUserId, RoleBinding::userId);
        if (teamId != null && !hasActiveTeam(current, teamId)
                && (existing == null || !Objects.equals(existing.teamId(), teamId))) {
            throw invalid("teamId must reference an active team");
        }
        RoleBinding binding = new RoleBinding(resolvedUserId, role, teamId, "active", actor.userId(), now);
        GovernanceConfiguration candidate = withRoleBindings(current,
                replace(current.roleBindings(), binding, RoleBinding::userId));
        validateConfiguration(candidate);
        save(candidate, existing == null ? "ROLE_BINDING_CREATED" : "ROLE_BINDING_UPDATED", "ROLE_BINDING",
                resolvedUserId, actor, requestId, Map.of("role", role));
        return binding;
    }

    public void deactivateRoleBinding(String userId, Actor actor, String requestId) {
        requireAdmin(actor);
        String resolvedUserId = id(userId, "userId");
        GovernanceConfiguration current = store.snapshot().configuration();
        RoleBinding existing = find(current.roleBindings(), resolvedUserId, RoleBinding::userId);
        if (existing == null || INACTIVE.equals(existing.status())) {
            return;
        }
        RoleBinding inactive = new RoleBinding(existing.userId(), existing.role(), existing.teamId(), INACTIVE,
                actor.userId(), Instant.now());
        GovernanceConfiguration candidate = withRoleBindings(current,
                replace(current.roleBindings(), inactive, RoleBinding::userId));
        validateConfiguration(candidate);
        save(candidate, "ROLE_BINDING_DEACTIVATED", "ROLE_BINDING", resolvedUserId, actor, requestId, Map.of());
    }

    public CategoryDefinition upsertCategory(TaxonomyMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        TaxonomyMutation value = requireTaxonomy(request);
        String code = stableId(value.code(), "code");
        CategoryDefinition existing = find(store.snapshot().configuration().categories(), code, CategoryDefinition::code);
        CategoryDefinition category = new CategoryDefinition(code, text(value.displayName(), "displayName"),
                optionalText(value.description()), sortOrder(value.sortOrder()), "active", actor.userId(), Instant.now());
        GovernanceConfiguration current = store.snapshot().configuration();
        GovernanceConfiguration candidate = withCategories(current,
                replace(current.categories(), category, CategoryDefinition::code));
        validateConfiguration(candidate);
        save(candidate, existing == null ? "CATEGORY_CREATED" : "CATEGORY_UPDATED", "CATEGORY", code, actor,
                requestId, Map.of("displayName", category.displayName()));
        return category;
    }

    public CategoryDefinition createCategory(TaxonomyMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request != null && find(store.snapshot().configuration().categories(), request.code(), CategoryDefinition::code) != null) {
            throw new ConfigConflictException("Duplicate category code: " + request.code());
        }
        return upsertCategory(request, actor, requestId);
    }

    public void deactivateCategory(String code, Actor actor, String requestId) {
        requireAdmin(actor);
        GovernanceConfiguration current = store.snapshot().configuration();
        CategoryDefinition existing = find(current.categories(), stableId(code, "code"), CategoryDefinition::code);
        if (existing == null || INACTIVE.equals(existing.status())) {
            return;
        }
        CategoryDefinition inactive = new CategoryDefinition(existing.code(), existing.displayName(), existing.description(),
                existing.sortOrder(), INACTIVE, actor.userId(), Instant.now());
        GovernanceConfiguration candidate = withCategories(current,
                replace(current.categories(), inactive, CategoryDefinition::code));
        validateConfiguration(candidate);
        save(candidate, "CATEGORY_DEACTIVATED", "CATEGORY", inactive.code(), actor, requestId, Map.of());
    }

    public TagDefinition upsertTag(TaxonomyMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        TaxonomyMutation value = requireTaxonomy(request);
        String code = stableId(value.code(), "code");
        GovernanceConfiguration current = store.snapshot().configuration();
        TagDefinition existing = find(current.tags(), code, TagDefinition::code);
        TagDefinition tag = new TagDefinition(code, text(value.displayName(), "displayName"),
                optionalText(value.description()), sortOrder(value.sortOrder()), "active", actor.userId(), Instant.now());
        GovernanceConfiguration candidate = withTags(current, replace(current.tags(), tag, TagDefinition::code));
        validateConfiguration(candidate);
        save(candidate, existing == null ? "TAG_CREATED" : "TAG_UPDATED", "TAG", code, actor, requestId,
                Map.of("displayName", tag.displayName()));
        return tag;
    }

    public TagDefinition createTag(TaxonomyMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request != null && find(store.snapshot().configuration().tags(), request.code(), TagDefinition::code) != null) {
            throw new ConfigConflictException("Duplicate tag code: " + request.code());
        }
        return upsertTag(request, actor, requestId);
    }

    public void deactivateTag(String code, Actor actor, String requestId) {
        requireAdmin(actor);
        GovernanceConfiguration current = store.snapshot().configuration();
        TagDefinition existing = find(current.tags(), stableId(code, "code"), TagDefinition::code);
        if (existing == null || INACTIVE.equals(existing.status())) {
            return;
        }
        TagDefinition inactive = new TagDefinition(existing.code(), existing.displayName(), existing.description(),
                existing.sortOrder(), INACTIVE, actor.userId(), Instant.now());
        GovernanceConfiguration candidate = withTags(current, replace(current.tags(), inactive, TagDefinition::code));
        validateConfiguration(candidate);
        save(candidate, "TAG_DEACTIVATED", "TAG", inactive.code(), actor, requestId, Map.of());
    }

    public CollectionDefinition upsertCollection(CollectionMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null) {
            throw invalid("Collection payload is required");
        }
        String collectionId = stableId(request.collectionId(), "collectionId");
        String name = text(request.name(), "name");
        String visibility = normalize(request.visibility());
        if (!VISIBILITIES.contains(visibility)) {
            throw invalid("visibility must be public or team");
        }
        String ownerTeamId = optionalText(request.ownerTeamId());
        List<String> skillIds = uniqueStableIds(request.skillIds(), "skillIds");
        skillIds.forEach(this::ensureSkillAvailable);
        GovernanceConfiguration current = store.snapshot().configuration();
        CollectionDefinition existing = find(current.collections(), collectionId, CollectionDefinition::collectionId);
        if ("team".equals(visibility) && !hasActiveTeam(current, ownerTeamId)
                && (existing == null || !Objects.equals(existing.ownerTeamId(), ownerTeamId))) {
            throw invalid("team-visible collection must reference an active team");
        }
        CollectionDefinition collection = new CollectionDefinition(collectionId, name,
                optionalText(request.description()), ownerTeamId, visibility, skillIds, sortOrder(request.sortOrder()),
                "active", actor.userId(), Instant.now());
        GovernanceConfiguration candidate = withCollections(current,
                replace(current.collections(), collection, CollectionDefinition::collectionId));
        validateConfiguration(candidate);
        save(candidate, existing == null ? "COLLECTION_CREATED" : "COLLECTION_UPDATED", "COLLECTION", collectionId,
                actor, requestId, Map.of("visibility", visibility));
        return collection;
    }

    public CollectionDefinition createCollection(CollectionMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request != null && find(store.snapshot().configuration().collections(), request.collectionId(), CollectionDefinition::collectionId) != null) {
            throw new ConfigConflictException("Duplicate collectionId: " + request.collectionId());
        }
        return upsertCollection(request, actor, requestId);
    }

    public CollectionDefinition addSkill(String collectionId, String skillId, Actor actor, String requestId) {
        requireAdmin(actor);
        String resolvedCollectionId = stableId(collectionId, "collectionId");
        String resolvedSkillId = stableId(skillId, "skillId");
        GovernanceConfiguration current = store.snapshot().configuration();
        CollectionDefinition existing = find(current.collections(), resolvedCollectionId, CollectionDefinition::collectionId);
        if (existing == null || !ACTIVE_STATUSES.contains(existing.status())) {
            throw new CollectionNotFoundException(resolvedCollectionId);
        }
        ensureSkillAvailable(resolvedSkillId);
        if (existing.skillIds().contains(resolvedSkillId)) {
            return existing;
        }
        List<String> members = new ArrayList<>(existing.skillIds());
        members.add(resolvedSkillId);
        CollectionDefinition updated = new CollectionDefinition(existing.collectionId(), existing.name(), existing.description(),
                existing.ownerTeamId(), existing.visibility(), members, existing.sortOrder(), existing.status(),
                actor.userId(), Instant.now());
        GovernanceConfiguration candidate = withCollections(current,
                replace(current.collections(), updated, CollectionDefinition::collectionId));
        validateConfiguration(candidate);
        save(candidate, "COLLECTION_SKILL_ADDED", "COLLECTION", resolvedCollectionId, actor, requestId,
                Map.of("skillId", resolvedSkillId));
        return updated;
    }

    public CollectionDefinition removeSkill(String collectionId, String skillId, Actor actor, String requestId) {
        requireAdmin(actor);
        String resolvedCollectionId = stableId(collectionId, "collectionId");
        String resolvedSkillId = stableId(skillId, "skillId");
        GovernanceConfiguration current = store.snapshot().configuration();
        CollectionDefinition existing = find(current.collections(), resolvedCollectionId, CollectionDefinition::collectionId);
        if (existing == null || !ACTIVE_STATUSES.contains(existing.status())) {
            throw new CollectionNotFoundException(resolvedCollectionId);
        }
        if (!existing.skillIds().contains(resolvedSkillId)) {
            return existing;
        }
        List<String> members = existing.skillIds().stream().filter(id -> !id.equals(resolvedSkillId)).toList();
        CollectionDefinition updated = new CollectionDefinition(existing.collectionId(), existing.name(), existing.description(),
                existing.ownerTeamId(), existing.visibility(), members, existing.sortOrder(), existing.status(),
                actor.userId(), Instant.now());
        GovernanceConfiguration candidate = withCollections(current,
                replace(current.collections(), updated, CollectionDefinition::collectionId));
        validateConfiguration(candidate);
        save(candidate, "COLLECTION_SKILL_REMOVED", "COLLECTION", resolvedCollectionId, actor, requestId,
                Map.of("skillId", resolvedSkillId));
        return updated;
    }

    public void deactivateCollection(String collectionId, Actor actor, String requestId) {
        requireAdmin(actor);
        GovernanceConfiguration current = store.snapshot().configuration();
        CollectionDefinition existing = find(current.collections(), stableId(collectionId, "collectionId"), CollectionDefinition::collectionId);
        if (existing == null || INACTIVE.equals(existing.status())) {
            return;
        }
        CollectionDefinition inactive = new CollectionDefinition(existing.collectionId(), existing.name(), existing.description(),
                existing.ownerTeamId(), existing.visibility(), existing.skillIds(), existing.sortOrder(), INACTIVE,
                actor.userId(), Instant.now());
        GovernanceConfiguration candidate = withCollections(current,
                replace(current.collections(), inactive, CollectionDefinition::collectionId));
        validateConfiguration(candidate);
        save(candidate, "COLLECTION_DEACTIVATED", "COLLECTION", inactive.collectionId(), actor, requestId, Map.of());
    }

    public PlatformPolicy updatePolicy(PolicyMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null) {
            throw invalid("Policy payload is required");
        }
        validatePolicyRequest(request);
        GovernanceConfiguration current = store.snapshot().configuration();
        String minimumClientVersion = text(request.minimumClientVersion(), "minimumClientVersion");
        String defaultVisibility = normalize(request.defaultCollectionVisibility());
        PlatformPolicy policy = new PlatformPolicy(current.platformPolicy().policyVersion() + 1,
                request.pageSizeOptions(), request.maxPageSize(), minimumClientVersion,
                defaultVisibility, actor.userId(), Instant.now());
        GovernanceConfiguration candidate = new GovernanceConfiguration(current.teams(), current.roleBindings(),
                current.categories(), current.tags(), current.collections(), policy);
        validateConfiguration(candidate);
        save(candidate, "PLATFORM_POLICY_UPDATED", "PLATFORM_POLICY", "platform", actor, requestId,
                Map.of("policyVersion", Integer.toString(policy.policyVersion())));
        return policy;
    }

    private void requireAdmin(Actor actor) {
        RoleGuard.require(actor, Set.of("admin"));
    }

    private void save(GovernanceConfiguration configuration, String action, String resourceType, String resourceId,
                      Actor actor, String requestId, Map<String, String> metadata) {
        Instant now = Instant.now();
        store.updateGovernanceConfiguration(configuration,
                new AuditEvent(UUID.randomUUID().toString(), action, resourceType, resourceId, actor.userId(),
                        actor.role(), requestId, now, metadata == null ? Map.of() : Map.copyOf(metadata)));
    }

    private void validateConfiguration(GovernanceConfiguration configuration) {
        unique(configuration.teams().stream().map(TeamDefinition::teamId).toList(), "teamId");
        unique(configuration.roleBindings().stream().map(RoleBinding::userId).toList(), "userId");
        unique(configuration.categories().stream().map(CategoryDefinition::code).toList(), "category code");
        unique(configuration.tags().stream().map(TagDefinition::code).toList(), "tag code");
        unique(configuration.collections().stream().map(CollectionDefinition::collectionId).toList(), "collectionId");
        for (TeamDefinition team : configuration.teams()) {
            stableId(team.teamId(), "teamId");
            text(team.name(), "name");
            uniqueTexts(team.memberUserIds(), "memberUserIds");
            if (!status(team.status())) {
                throw invalid("team status must be active or inactive");
            }
        }
        for (RoleBinding binding : configuration.roleBindings()) {
            id(binding.userId(), "userId");
            if (!ROLES.contains(normalize(binding.role()))) {
                throw invalid("role must be viewer, maintainer, reviewer, or admin");
            }
            if (!status(binding.status())) {
                throw invalid("role binding status must be active or inactive");
            }
        }
        for (CategoryDefinition category : configuration.categories()) {
            stableId(category.code(), "code");
            text(category.displayName(), "displayName");
            sortOrder(category.sortOrder());
            if (!status(category.status())) {
                throw invalid("category status must be active or inactive");
            }
        }
        for (TagDefinition tag : configuration.tags()) {
            stableId(tag.code(), "code");
            text(tag.displayName(), "displayName");
            sortOrder(tag.sortOrder());
            if (!status(tag.status())) {
                throw invalid("tag status must be active or inactive");
            }
        }
        for (CollectionDefinition collection : configuration.collections()) {
            stableId(collection.collectionId(), "collectionId");
            text(collection.name(), "name");
            String visibility = normalize(collection.visibility());
            if (!VISIBILITIES.contains(visibility)) {
                throw invalid("visibility must be public or team");
            }
            uniqueStableIds(collection.skillIds(), "skillIds");
            if (!status(collection.status())) {
                throw invalid("collection status must be active or inactive");
            }
        }
        validatePolicy(configuration.platformPolicy());
    }

    private void validatePolicyRequest(PolicyMutation request) {
        if (request.pageSizeOptions() == null || request.pageSizeOptions().isEmpty()) {
            throw invalid("pageSizeOptions must not be empty");
        }
        text(request.minimumClientVersion(), "minimumClientVersion");
        if (!VISIBILITIES.contains(normalize(request.defaultCollectionVisibility()))) {
            throw invalid("defaultCollectionVisibility must be public or team");
        }
        validatePolicy(new PlatformPolicy(1, request.pageSizeOptions(), request.maxPageSize(),
                request.minimumClientVersion(), request.defaultCollectionVisibility(), null, null));
    }

    private void validatePolicy(PlatformPolicy policy) {
        if (policy.policyVersion() < 1 || policy.maxPageSize() <= 0) {
            throw invalid("policy version and maxPageSize must be positive");
        }
        validateSemVer(policy.minimumClientVersion());
        if (!VISIBILITIES.contains(normalize(policy.defaultCollectionVisibility()))) {
            throw invalid("defaultCollectionVisibility must be public or team");
        }
        if (policy.pageSizeOptions() == null || policy.pageSizeOptions().isEmpty()) {
            throw invalid("pageSizeOptions must not be empty");
        }
        int previous = 0;
        for (Integer option : policy.pageSizeOptions()) {
            if (option == null || option <= previous || option > policy.maxPageSize()) {
                throw invalid("pageSizeOptions must be strictly increasing and within maxPageSize");
            }
            previous = option;
        }
    }

    private void validateSemVer(String value) {
        if (value == null || !value.matches("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$")) {
            throw invalid("minimumClientVersion must be a semantic version");
        }
    }

    private void ensureSkillAvailable(String skillId) {
        stableId(skillId, "skillId");
        List<SkillVersion> versions = store.snapshot().versions().stream()
                .filter(version -> skillId.equals(version.skillId())).toList();
        if (!versions.isEmpty()) {
            if (versions.stream().anyMatch(version -> Set.of("published", "deprecated").contains(normalize(version.status())))) {
                return;
            }
            if (versions.stream().anyMatch(version -> "withdrawn".equals(normalize(version.status())))) {
                throw new InvalidLifecycleRequestException("Withdrawn Skill cannot be added to a collection");
            }
        }
        if (catalogService == null) {
            throw new SkillNotFoundException(skillId);
        }
        try {
            catalogService.detail(skillId);
        } catch (SkillNotFoundException exception) {
            throw exception;
        }
    }

    private boolean visible(CollectionDefinition collection, Actor actor, List<RoleBinding> bindings,
                            List<TeamDefinition> teams) {
        if (!ACTIVE_STATUSES.contains(collection.status())) {
            return false;
        }
        if ("public".equalsIgnoreCase(collection.visibility())) {
            return true;
        }
        String ownerTeamId = collection.ownerTeamId();
        if (ownerTeamId == null || ownerTeamId.isBlank()) {
            return false;
        }
        if (Set.of("admin", "reviewer").contains(actor.role())) {
            return true;
        }
        return bindings.stream().anyMatch(binding -> actor.userId().equals(binding.userId())
                && ACTIVE_STATUSES.contains(binding.status()) && ownerTeamId.equals(binding.teamId()));
    }

    private boolean hasActiveTeam(GovernanceConfiguration configuration, String teamId) {
        return teamId != null && configuration.teams().stream().anyMatch(team -> teamId.equals(team.teamId())
                && ACTIVE_STATUSES.contains(team.status()));
    }

    private <T> List<T> active(List<T> values) {
        return values.stream().filter(value -> {
            String status = statusOf(value);
            return status == null || ACTIVE_STATUSES.contains(status);
        }).toList();
    }

    private String statusOf(Object value) {
        if (value instanceof TeamDefinition team) return normalize(team.status());
        if (value instanceof RoleBinding binding) return normalize(binding.status());
        if (value instanceof CategoryDefinition category) return normalize(category.status());
        if (value instanceof TagDefinition tag) return normalize(tag.status());
        if (value instanceof CollectionDefinition collection) return normalize(collection.status());
        return null;
    }

    private boolean status(String value) {
        return ACTIVE_STATUSES.contains(normalize(value)) || INACTIVE.equals(normalize(value));
    }

    private TaxonomyMutation requireTaxonomy(TaxonomyMutation request) {
        if (request == null) {
            throw invalid("Taxonomy payload is required");
        }
        return request;
    }

    private int sortOrder(int value) {
        if (value < 0) {
            throw invalid("sortOrder must not be negative");
        }
        return value;
    }

    private String id(String value, String field) {
        String normalized = text(value, field);
        if (normalized.length() > 128) {
            throw invalid(field + " must be at most 128 characters");
        }
        return normalized;
    }

    private String stableId(String value, String field) {
        String normalized = id(value, field);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw invalid(field + " must be a stable URL-safe identifier");
        }
        return normalized;
    }

    private String text(String value, String field) {
        String normalized = trimmed(value);
        if (normalized == null) {
            throw invalid(field + " is required");
        }
        return normalized;
    }

    private String optionalText(String value) {
        String normalized = trimmed(value);
        return normalized == null ? null : normalized;
    }

    private String normalize(String value) {
        String trimmed = trimmed(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<String> uniqueTexts(List<String> values, String field) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = id(value, field);
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private List<String> uniqueStableIds(List<String> values, String field) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = stableId(value, field);
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private void unique(List<String> values, String field) {
        Set<String> normalized = values.stream().map(this::normalize).collect(Collectors.toSet());
        if (normalized.size() != values.size()) {
            throw new ConfigConflictException("Duplicate " + field);
        }
    }

    private <T> T find(List<T> values, String id, Function<T, String> key) {
        return values.stream().filter(value -> Objects.equals(normalize(key.apply(value)), normalize(id)))
                .findFirst().orElse(null);
    }

    private <T> List<T> replace(List<T> values, T replacement, Function<T, String> key) {
        List<T> result = new ArrayList<>();
        boolean replaced = false;
        for (T value : values) {
            if (Objects.equals(normalize(key.apply(value)), normalize(key.apply(replacement)))) {
                result.add(replacement);
                replaced = true;
            } else {
                result.add(value);
            }
        }
        if (!replaced) {
            result.add(replacement);
        }
        return List.copyOf(result);
    }

    private GovernanceConfiguration withTeams(GovernanceConfiguration current, List<TeamDefinition> teams) {
        return new GovernanceConfiguration(teams, current.roleBindings(), current.categories(), current.tags(),
                current.collections(), current.platformPolicy());
    }

    private GovernanceConfiguration withRoleBindings(GovernanceConfiguration current, List<RoleBinding> bindings) {
        return new GovernanceConfiguration(current.teams(), bindings, current.categories(), current.tags(),
                current.collections(), current.platformPolicy());
    }

    private GovernanceConfiguration withCategories(GovernanceConfiguration current, List<CategoryDefinition> categories) {
        return new GovernanceConfiguration(current.teams(), current.roleBindings(), categories, current.tags(),
                current.collections(), current.platformPolicy());
    }

    private GovernanceConfiguration withTags(GovernanceConfiguration current, List<TagDefinition> tags) {
        return new GovernanceConfiguration(current.teams(), current.roleBindings(), current.categories(), tags,
                current.collections(), current.platformPolicy());
    }

    private GovernanceConfiguration withCollections(GovernanceConfiguration current, List<CollectionDefinition> collections) {
        return new GovernanceConfiguration(current.teams(), current.roleBindings(), current.categories(), current.tags(),
                collections, current.platformPolicy());
    }

    private InvalidLifecycleRequestException invalid(String message) {
        return new InvalidLifecycleRequestException(message);
    }
}
