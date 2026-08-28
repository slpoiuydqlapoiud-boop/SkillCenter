package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillVisibilityContext;
import com.huawei.skillcenter.events.InvocationEvent;
import com.huawei.skillcenter.events.InvocationEventService;
import com.huawei.skillcenter.skill.SkillCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class VersionLifecycleService {
    private final GovernanceStore store;
    private final InvocationEventService invocationEventService;
    private final SkillCatalogService catalogService;
    private final SkillAuthorizationService authorizationService;

    @Autowired
    public VersionLifecycleService(GovernanceStore store, InvocationEventService invocationEventService,
                                   SkillCatalogService catalogService,
                                   SkillAuthorizationService authorizationService) {
        this.store = store;
        this.invocationEventService = invocationEventService;
        this.catalogService = catalogService;
        this.authorizationService = authorizationService;
    }

    public VersionLifecycleService(GovernanceStore store, InvocationEventService invocationEventService,
                                   SkillCatalogService catalogService) {
        this(store, invocationEventService, catalogService, null);
    }

    public VersionLifecycleService(GovernanceStore store, InvocationEventService invocationEventService) {
        this(store, invocationEventService, null, null);
    }

    public SkillVersion deprecate(String skillId, String version, VersionLifecycleRequest request,
                                   Actor actor, String requestId) {
        return transition(skillId, version, request, actor, requestId, "deprecated", "VERSION_DEPRECATED");
    }

    public SkillVersion withdraw(String skillId, String version, VersionLifecycleRequest request,
                                 Actor actor, String requestId) {
        return transition(skillId, version, request, actor, requestId, "withdrawn", "VERSION_WITHDRAWN");
    }

    public VersionImpact impact(String skillId, String version, Actor actor) {
        SkillVersion target = find(skillId, version);
        requireImpactAccess(target, actor);
        var installations = store.snapshot().installations().stream()
                .filter(item -> skillId.equals(item.skillId()) && version.equals(item.version()))
                .toList();
        long activeInstallations = installations.stream()
                .filter(item -> Set.of("installed", "installing").contains(item.status()))
                .count();
        long userCount = installations.stream().map(InstallationRecord::requestedBy).filter(java.util.Objects::nonNull)
                .distinct().count();
        long teamCount = installations.stream().map(InstallationRecord::teamId).filter(value -> value != null && !value.isBlank())
                .distinct().count();
        Map<String, Long> clientTypes = installations.stream()
                .map(InstallationRecord::clientType)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
        var invocationEvents = invocationEventService.events().stream()
                .filter(item -> skillId.equals(item.skillId()) && version.equals(item.version()))
                .toList();
        long activeInvocationUsers = invocationEvents.stream()
                .map(InvocationEvent::subject)
                .filter(java.util.Objects::nonNull)
                .map(InvocationEvent.Subject::userId)
                .filter(value -> value != null && !value.isBlank())
                .distinct().count();
        return new VersionImpact(skillId, version, installations.size(), activeInstallations, userCount, teamCount,
                clientTypes, invocationEvents.size(), activeInvocationUsers, target.replacementVersion());
    }

    public SkillVersion find(String skillId, String version) {
        return store.snapshot().versions().stream()
                .filter(item -> skillId.equals(item.skillId()) && version.equals(item.version()))
                .findFirst()
                .orElseThrow(() -> new SkillVersionNotFoundException(skillId, version));
    }

    private SkillVersion transition(String skillId, String version, VersionLifecycleRequest request, Actor actor,
                                    String requestId, String targetStatus, String auditAction) {
        RoleGuard.require(actor, Set.of("admin"));
        if (authorizationService != null) {
            authorizationService.requireManage(skillId, actor);
        }
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new InvalidLifecycleRequestException("Lifecycle reason is required");
        }
        SkillVersion current = find(skillId, version);
        if (!allowed(current.status(), targetStatus)) {
            throw new VersionStateConflictException("Version cannot transition from " + current.status()
                    + " to " + targetStatus);
        }
        String replacement = normalize(request.replacementVersion());
        if (replacement != null) {
            SkillVersion replacementVersion = findReplacement(skillId, version, replacement);
            if (!Set.of("published", "deprecated").contains(replacementVersion.status())) {
                throw new InvalidLifecycleRequestException("Replacement version must be published or deprecated");
            }
        }
        Instant now = Instant.now();
        SkillVersion updated = new SkillVersion(current.packageId(), current.skillId(), current.version(), targetStatus,
                current.sha256(), current.sizeBytes(), current.artifactPath(), current.uploadedBy(), current.uploadedAt(),
                current.publishedBy(), current.publishedAt(), current.reviewId(), request.reason().trim(), replacement,
                actor.userId(), now, current.riskLevel(), current.securityEvidence());
        AuditEvent lifecycleAudit = new AuditEvent(UUID.randomUUID().toString(), auditAction, "SKILL_VERSION",
                current.packageId(), actor.userId(), actor.role(), requestId, now,
                Map.of("skillId", skillId, "version", version, "reason", request.reason().trim(),
                        "replacementVersion", replacement == null ? "" : replacement));
        String notificationTitle = "withdrawn".equals(targetStatus) ? "Skill 版本已下架" : "Skill 版本已废弃";
        String notificationDetail = "Skill " + skillId + "@" + version + " 已"
                + ("withdrawn".equals(targetStatus) ? "下架" : "废弃") + "。原因："
                + request.reason().trim() + (replacement == null ? "" : "；替代版本：" + replacement);
        GovernanceStore.VersionTransitionResult result = store.transitionVersion(updated, lifecycleAudit,
                "withdrawn".equals(targetStatus) ? "VERSION_WITHDRAWN" : null,
                "lifecycle", notificationTitle, notificationDetail, now);
        return result.snapshot().versions().stream()
                .filter(item -> item.packageId().equals(updated.packageId()))
                .findFirst()
                .orElse(updated);
    }

    private SkillVersion findReplacement(String skillId, String targetVersion, String replacement) {
        if (replacement.equals(targetVersion)) {
            throw new InvalidLifecycleRequestException("Replacement version must be different from target version");
        }
        return store.snapshot().versions().stream()
                .filter(item -> skillId.equals(item.skillId()) && replacement.equals(item.version()))
                .findFirst()
                .orElseThrow(() -> new InvalidLifecycleRequestException("Replacement version does not exist"));
    }

    private void requireImpactAccess(SkillVersion target, Actor actor) {
        if (authorizationService != null) {
            RoleGuard.require(actor, Set.of("admin", "reviewer", "maintainer"));
            authorizationService.requireVisible(target.skillId(), actor, SkillVisibilityContext.GOVERNANCE);
            return;
        }
        RoleGuard.require(actor, Set.of("admin", "reviewer", "maintainer"));
        if (RoleGuard.isDeveloper(actor) && !actor.userId().equals(target.uploadedBy())) {
            boolean ownsCatalogSkill = false;
            if (catalogService != null) {
                try {
                    var skill = catalogService.detail(target.skillId());
                    ownsCatalogSkill = actor.userId().equals(skill.owner()) || actor.userId().equals(skill.team());
                } catch (RuntimeException ignored) {
                    ownsCatalogSkill = false;
                }
            }
            if (!ownsCatalogSkill) {
                throw new ForbiddenException("Maintainer can only inspect owned versions");
            }
        }
    }

    private boolean allowed(String current, String target) {
        return ("published".equals(current) && Set.of("deprecated", "withdrawn").contains(target))
                || ("deprecated".equals(current) && "withdrawn".equals(target));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
