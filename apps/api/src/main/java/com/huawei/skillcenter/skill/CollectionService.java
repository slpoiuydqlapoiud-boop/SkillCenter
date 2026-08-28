package com.huawei.skillcenter.skill;

import com.huawei.skillcenter.access.SkillNotVisibleException;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.CollectionDefinition;
import com.huawei.skillcenter.governance.CollectionNotFoundException;
import com.huawei.skillcenter.governance.GovernanceConfigurationService;
import com.huawei.skillcenter.governance.GovernanceConfigurationView;
import com.huawei.skillcenter.governance.InvalidLifecycleRequestException;
import com.huawei.skillcenter.governance.PlatformPolicy;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;

@Service
public class CollectionService {
    private final GovernanceConfigurationService governanceService;
    private final SkillCatalogService catalogService;

    public CollectionService(GovernanceConfigurationService governanceService, SkillCatalogService catalogService) {
        this.governanceService = governanceService;
        this.catalogService = catalogService;
    }

    public List<CollectionDefinition> list(Actor actor, int page, int pageSize) {
        return page(actor, page, pageSize, "", "order").items();
    }

    public List<CollectionDefinition> list(Actor actor, int page, int pageSize, String query, String sort) {
        return page(actor, page, pageSize, query, sort).items();
    }

    public PageResult<CollectionDefinition> page(Actor actor, int page, int pageSize, String query, String sort) {
        GovernanceConfigurationView view = governanceService.read(actor);
        validatePage(page, pageSize, view.platformPolicy());
        List<CollectionDefinition> sorted = sorted(view.collections(), query, sort, actor);
        int from = Math.min((page - 1) * pageSize, sorted.size());
        int to = Math.min(from + pageSize, sorted.size());
        return new PageResult<>(sorted.subList(from, to), page, pageSize, sorted.size());
    }

    public CollectionDetail detail(String collectionId, Actor actor) {
        GovernanceConfigurationView view = governanceService.read(actor);
        CollectionDefinition collection = view.collections().stream()
                .filter(item -> item.collectionId().equals(collectionId))
                .findFirst().orElseThrow(() -> new CollectionNotFoundException(collectionId));
        List<SkillSummary> skills = collection.skillIds().stream()
                .map(skillId -> findActiveSkill(skillId, actor))
                .flatMap(java.util.Optional::stream)
                .toList();
        return new CollectionDetail(collection, skills);
    }

    private java.util.Optional<SkillSummary> findActiveSkill(String skillId, Actor actor) {
        try {
            SkillRecord skill = catalogService.detail(skillId, actor);
            if (skill == null || skill.status() == null
                    || !java.util.Set.of("published", "deprecated").contains(skill.status().toLowerCase(java.util.Locale.ROOT))) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(SkillSummary.from(skill));
        } catch (SkillNotFoundException | SkillNotVisibleException exception) {
            return java.util.Optional.empty();
        }
    }

    private List<CollectionDefinition> sorted(List<CollectionDefinition> values, String query, String sort, Actor actor) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Comparator<CollectionDefinition> comparator = switch (normalizeSort(sort)) {
            case "order" -> Comparator.comparingInt(CollectionDefinition::sortOrder);
            case "downloads" -> Comparator.comparingInt((CollectionDefinition item) -> aggregate(item, metrics -> metrics == null ? 0 : metrics.installs(), actor));
            case "calls" -> Comparator.comparingInt((CollectionDefinition item) -> aggregate(item, metrics -> metrics == null ? 0 : metrics.calls(), actor));
            case "favorites" -> Comparator.comparingInt((CollectionDefinition item) -> aggregate(item, metrics -> metrics == null ? 0 : metrics.favorites(), actor));
            default -> Comparator.comparingLong(this::updatedAt);
        };
        var result = values.stream()
                .filter(item -> normalizedQuery.isBlank() || searchable(item).contains(normalizedQuery))
                .sorted(("order".equals(normalizeSort(sort)) ? comparator : comparator.reversed())
                        .thenComparing(CollectionDefinition::collectionId))
                .toList();
        return result;
    }

    private String searchable(CollectionDefinition item) {
        return String.join(" ", item.name(), item.description() == null ? "" : item.description(),
                item.ownerTeamId() == null ? "" : item.ownerTeamId()).toLowerCase(Locale.ROOT);
    }

    private int aggregate(CollectionDefinition collection, ToIntFunction<SkillMetrics> metric, Actor actor) {
        return collection.skillIds().stream().map(skillId -> findActiveSkill(skillId, actor))
                .flatMap(java.util.Optional::stream)
                .map(SkillSummary::metrics)
                .mapToInt(metrics -> metric.applyAsInt(metrics == null ? new SkillMetrics(0, 0, 0, 0, 0) : metrics))
                .sum();
    }

    private long updatedAt(CollectionDefinition item) {
        return item.updatedAt() == null ? item.sortOrder() : item.updatedAt().toEpochMilli();
    }

    private String normalizeSort(String sort) {
        if (sort == null) {
            return "updated";
        }
        String value = sort.trim().toLowerCase(Locale.ROOT);
        return Set.of("order", "downloads", "calls", "favorites", "updated").contains(value) ? value : "updated";
    }

    private void validatePage(int page, int pageSize, PlatformPolicy policy) {
        if (page < 1 || pageSize < 1 || pageSize > policy.maxPageSize()
                || !policy.pageSizeOptions().contains(pageSize)) {
            throw new InvalidLifecycleRequestException("page and pageSize do not match the platform policy");
        }
    }

    public record CollectionDetail(CollectionDefinition collection, List<SkillSummary> skills) {
    }
}
