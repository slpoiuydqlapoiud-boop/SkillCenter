package com.huawei.skillcenter.analytics;

import com.huawei.skillcenter.events.InvocationEvent;
import com.huawei.skillcenter.events.InvocationEventService;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationRecord;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.skill.PageResult;
import com.huawei.skillcenter.skill.SkillCatalogService;
import com.huawei.skillcenter.skill.SkillQuery;
import com.huawei.skillcenter.skill.SkillSummary;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class AnalyticsService {
    private final InvocationEventService eventService;
    private final SkillCatalogService catalogService;
    private final GovernanceStore governanceStore;
    private final AnalyticsQueryParser queryParser = new AnalyticsQueryParser();
    private final AnalyticsAggregator aggregator = new AnalyticsAggregator();

    public AnalyticsService(InvocationEventService eventService,
                            SkillCatalogService catalogService,
                            GovernanceStore governanceStore) {
        this.eventService = eventService;
        this.catalogService = catalogService;
        this.governanceStore = governanceStore;
    }

    public AnalyticsOverview overview() {
        AnalyticsQuery query = queryParser.parse(null, null, null, null, null, null,
                LocalDate.now(AnalyticsQueryParser.BEIJING_ZONE));
        return overview(query, new Actor("local-user", "admin"));
    }

    public AnalyticsOverview overview(AnalyticsQuery query, Actor actor) {
        RoleGuard.require(actor, java.util.Set.of("viewer", "maintainer", "reviewer", "admin"));
        PageResult<SkillSummary> page = catalogService.list(new SkillQuery("", "", "", "", 1, 50));
        List<SkillSummary> skills = page.items();
        List<InvocationEvent> events = eventService.events().stream()
                .filter(event -> visibleEvent(event, skills, actor))
                .toList();
        List<InstallationRecord> installations = governanceStore.snapshot().installations().stream()
                .filter(installation -> visibleInstallation(installation, skills, actor))
                .toList();
        return aggregator.aggregate(query, skills, events, installations, eventService.ingestionStats());
    }

    private boolean visibleEvent(InvocationEvent event, List<SkillSummary> skills, Actor actor) {
        if ("admin".equals(actor.role()) || "reviewer".equals(actor.role())) {
            return true;
        }
        if ("viewer".equals(actor.role())) {
            return event.subject() != null && actor.userId().equals(event.subject().userId());
        }
        return skills.stream()
                .filter(skill -> skill.id().equals(event.skillId()))
                .anyMatch(skill -> actor.userId().equals(skill.owner()) || actor.userId().equals(skill.team()));
    }

    private boolean visibleInstallation(InstallationRecord installation, List<SkillSummary> skills, Actor actor) {
        if ("admin".equals(actor.role()) || "reviewer".equals(actor.role())) {
            return true;
        }
        if ("viewer".equals(actor.role())) {
            return actor.userId().equals(installation.requestedBy());
        }
        return skills.stream()
                .filter(skill -> skill.id().equals(installation.skillId()))
                .anyMatch(skill -> actor.userId().equals(skill.owner()) || actor.userId().equals(skill.team()));
    }
}
