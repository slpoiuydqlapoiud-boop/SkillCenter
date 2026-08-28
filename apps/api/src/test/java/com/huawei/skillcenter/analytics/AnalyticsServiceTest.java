package com.huawei.skillcenter.analytics;

import com.huawei.skillcenter.events.InvocationEventService;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.skill.PageResult;
import com.huawei.skillcenter.skill.SkillCatalogService;
import com.huawei.skillcenter.skill.SkillMetrics;
import com.huawei.skillcenter.skill.SkillQuery;
import com.huawei.skillcenter.skill.SkillSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void overviewUsesActorAwareCatalogList() {
        SkillCatalogService catalog = mock(SkillCatalogService.class);
        GovernanceStore store = new GovernanceStore(tempDir.resolve("analytics.json"), List.of());
        InvocationEventService events = new InvocationEventService();
        AnalyticsService service = new AnalyticsService(events, catalog, store);
        Actor actor = new Actor("alice", "viewer");
        AnalyticsQuery query = new AnalyticsQuery(AnalyticsQuery.RangeType.DAYS_7,
                LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 24), null, null, null, ZoneId.of("UTC"));
        SkillSummary visible = new SkillSummary("visible", "Visible", "1.0.0", "desc", "other", List.of(),
                "low", "low", "team-a", "owner", "", "blue", "published", "2026-08-24",
                new SkillMetrics(0, 0, 0, 0, 0));

        when(catalog.list(any(SkillQuery.class))).thenReturn(new PageResult<>(List.of(), 1, 50, 0));
        when(catalog.list(any(SkillQuery.class), eq(actor))).thenReturn(new PageResult<>(List.of(visible), 1, 50, 1));

        AnalyticsOverview overview = service.overview(query, actor);

        assertThat(overview.topSkills()).extracting(AnalyticsOverview.TopSkill::id).containsExactly("visible");
        verify(catalog).list(any(SkillQuery.class), eq(actor));
    }
}
