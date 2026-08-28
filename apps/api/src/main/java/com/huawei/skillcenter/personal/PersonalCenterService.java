package com.huawei.skillcenter.personal;

import com.huawei.skillcenter.access.SkillNotVisibleException;
import com.huawei.skillcenter.events.InvocationEvent;
import com.huawei.skillcenter.events.InvocationEventService;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.FavoriteRecord;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationRecord;
import com.huawei.skillcenter.governance.InvalidLifecycleRequestException;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.skill.PageResult;
import com.huawei.skillcenter.skill.SkillCatalogService;
import com.huawei.skillcenter.skill.SkillNotFoundException;
import com.huawei.skillcenter.skill.SkillQuery;
import com.huawei.skillcenter.skill.SkillRecord;
import com.huawei.skillcenter.skill.SkillSummary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PersonalCenterService {
    private final GovernanceStore store;
    private final SkillCatalogService catalogService;
    private final InvocationEventService invocationEventService;

    public PersonalCenterService(GovernanceStore store, SkillCatalogService catalogService,
                                 InvocationEventService invocationEventService) {
        this.store = store;
        this.catalogService = catalogService;
        this.invocationEventService = invocationEventService;
    }

    public List<InstallationRecord> installations(Actor actor, String status, String skillId, String clientType) {
        requireActor(actor);
        return store.snapshot().installations().stream()
                .filter(item -> actor.userId().equals(item.requestedBy()))
                .filter(item -> blank(status) || status.equalsIgnoreCase(item.status()))
                .filter(item -> blank(skillId) || skillId.equals(item.skillId()))
                .filter(item -> blank(clientType) || clientType.equalsIgnoreCase(item.clientType()))
                .sorted(Comparator.comparing(InstallationRecord::requestedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<PersonalSkillView> mySkills(Actor actor) {
        requireActor(actor);
        Map<String, PersonalSkillView> result = new LinkedHashMap<>();
        PageResult<SkillSummary> visible = catalogService.list(new SkillQuery("", "", "", "", 1, 500), actor);
        for (SkillSummary summary : visible.items()) {
            if (actor.userId().equals(summary.owner()) || actor.userId().equals(summary.team())) {
                result.put(summary.id(), fromSummary(summary));
            }
        }
        store.snapshot().versions().stream()
                .filter(version -> actor.userId().equals(version.uploadedBy()))
                .sorted(Comparator.comparing(SkillVersion::publishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(version -> result.putIfAbsent(version.skillId(), fromVersion(version)));
        return result.values().stream().sorted(Comparator.comparing(PersonalSkillView::id)).toList();
    }

    public List<PersonalSkillView> favorites(Actor actor) {
        requireActor(actor);
        return store.favoritesForUser(actor.userId()).stream()
                .sorted(Comparator.comparing(FavoriteRecord::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(FavoriteRecord::skillId)
                .map(skillId -> lookupSkill(skillId, actor))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    public PersonalSkillView addFavorite(Actor actor, String skillId, String requestId) {
        requireActor(actor);
        PersonalSkillView skill = lookupSkillOrThrow(skillId, actor);
        Instant now = Instant.now();
        store.addFavorite(new FavoriteRecord(actor.userId(), skillId, now), audit("FAVORITE_ADDED", skillId, actor, requestId, now));
        return skill;
    }

    public void removeFavorite(Actor actor, String skillId, String requestId) {
        requireActor(actor);
        Instant now = Instant.now();
        store.removeFavorite(actor.userId(), skillId, audit("FAVORITE_REMOVED", skillId, actor, requestId, now));
    }

    public InvocationHistoryPage invocations(Actor actor, OffsetDateTime from, OffsetDateTime to,
                                              String skillId, String status, int page, int pageSize) {
        requireActor(actor);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new InvalidLifecycleRequestException("page must be >= 1 and pageSize must be between 1 and 100");
        }
        List<InvocationHistoryView> filtered = invocationEventService.events().stream()
                .filter(event -> event.subject() != null && actor.userId().equals(event.subject().userId()))
                .filter(event -> from == null || !event.occurredAt().isBefore(from))
                .filter(event -> to == null || !event.occurredAt().isAfter(to))
                .filter(event -> blank(skillId) || skillId.equals(event.skillId()))
                .filter(event -> blank(status) || status.equalsIgnoreCase(event.status()))
                .sorted(Comparator.comparing(InvocationEvent::occurredAt).reversed())
                .map(event -> new InvocationHistoryView(event.occurredAt(), event.skillId(), event.version(),
                        event.client() == null ? null : event.client().type(),
                        event.client() == null ? null : event.client().version(), event.status(), event.durationMs(),
                        event.errorCode()))
                .toList();
        int fromIndex = Math.min((page - 1) * pageSize, filtered.size());
        int toIndex = Math.min(fromIndex + pageSize, filtered.size());
        return new InvocationHistoryPage(filtered.subList(fromIndex, toIndex), page, pageSize, filtered.size());
    }

    private java.util.Optional<PersonalSkillView> lookupSkill(String skillId, Actor actor) {
        try {
            return java.util.Optional.of(fromRecord(catalogService.detail(skillId, actor)));
        } catch (SkillNotVisibleException hidden) {
            return java.util.Optional.empty();
        } catch (SkillNotFoundException missing) {
            return store.snapshot().versions().stream()
                    .filter(version -> skillId.equals(version.skillId()))
                    .max(Comparator.comparing(SkillVersion::publishedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(this::fromVersion)
                    .map(java.util.Optional::of)
                    .orElseGet(() -> java.util.Optional.of(new PersonalSkillView(skillId, skillId, "", "unknown", "", "", "", "",
                            new com.huawei.skillcenter.skill.SkillMetrics(0, 0, 0, 0, 0), false)));
        }
    }

    private PersonalSkillView lookupSkillOrThrow(String skillId, Actor actor) {
        if (store.snapshot().versions().stream().noneMatch(version -> skillId.equals(version.skillId()))) {
            catalogService.detail(skillId, actor);
        }
        return lookupSkill(skillId, actor).orElseThrow(SkillNotVisibleException::new);
    }

    private PersonalSkillView fromRecord(SkillRecord record) {
        return new PersonalSkillView(record.id(), record.name(), record.version(), record.status(), record.description(),
                record.team(), record.owner(), record.icon(), record.metrics(), "withdrawn".equals(record.status()));
    }

    private PersonalSkillView fromSummary(SkillSummary summary) {
        return new PersonalSkillView(summary.id(), summary.name(), summary.version(), summary.status(), summary.description(),
                summary.team(), summary.owner(), summary.icon(), summary.metrics(), "withdrawn".equals(summary.status()));
    }

    private PersonalSkillView fromVersion(SkillVersion version) {
        return new PersonalSkillView(version.skillId(), version.skillId(), version.version(), version.status(), "",
                version.uploadedBy(), version.uploadedBy(), "", new com.huawei.skillcenter.skill.SkillMetrics(0, 0, 0, 0, 0),
                "withdrawn".equalsIgnoreCase(version.status()));
    }

    private AuditEvent audit(String action, String skillId, Actor actor, String requestId, Instant now) {
        return new AuditEvent(UUID.randomUUID().toString(), action, "FAVORITE", skillId, actor.userId(), actor.role(),
                requestId, now, Map.of("skillId", skillId));
    }

    private void requireActor(Actor actor) {
        RoleGuard.require(actor, Set.of("viewer", "maintainer", "reviewer", "admin"));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
