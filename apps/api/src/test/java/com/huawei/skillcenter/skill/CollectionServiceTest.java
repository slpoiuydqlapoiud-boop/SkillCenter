package com.huawei.skillcenter.skill;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.CollectionDefinition;
import com.huawei.skillcenter.governance.GovernanceConfigurationView;
import com.huawei.skillcenter.governance.GovernanceConfigurationService;
import com.huawei.skillcenter.governance.PlatformPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionServiceTest {
    @Test
    void detailUsesActorAwareCatalogDetail() {
        GovernanceConfigurationService governance = mock(GovernanceConfigurationService.class);
        SkillCatalogService catalog = mock(SkillCatalogService.class);
        CollectionService service = new CollectionService(governance, catalog);
        Actor actor = new Actor("alice", "viewer");
        CollectionDefinition collection = new CollectionDefinition("featured", "Featured", "", null,
                "public", List.of("visible", "hidden"), 1, "active", "admin", Instant.parse("2026-08-24T00:00:00Z"));
        when(governance.read(actor)).thenReturn(view(collection));
        when(catalog.detail("visible", actor)).thenReturn(skill("visible", 12));
        when(catalog.detail("hidden", actor)).thenThrow(new SkillNotFoundException("hidden"));

        CollectionService.CollectionDetail detail = service.detail("featured", actor);

        assertThat(detail.skills()).extracting(SkillSummary::id).containsExactly("visible");
        verify(catalog).detail("visible", actor);
        verify(catalog).detail("hidden", actor);
        verify(catalog, never()).detail("visible");
        verify(catalog, never()).detail("hidden");
    }

    @Test
    void sortingAggregatesUseActorAwareCatalogDetail() {
        GovernanceConfigurationService governance = mock(GovernanceConfigurationService.class);
        SkillCatalogService catalog = mock(SkillCatalogService.class);
        CollectionService service = new CollectionService(governance, catalog);
        Actor actor = new Actor("alice", "viewer");
        CollectionDefinition featured = new CollectionDefinition("featured", "Featured", "", null,
                "public", List.of("visible", "hidden"), 1, "active", "admin", Instant.parse("2026-08-24T00:00:00Z"));
        CollectionDefinition secondary = new CollectionDefinition("secondary", "Secondary", "", null,
                "public", List.of("other"), 2, "active", "admin", Instant.parse("2026-08-23T00:00:00Z"));
        when(governance.read(actor)).thenReturn(view(featured, secondary));
        when(catalog.detail("visible", actor)).thenReturn(skill("visible", 12));
        when(catalog.detail("hidden", actor)).thenThrow(new SkillNotFoundException("hidden"));
        when(catalog.detail("other", actor)).thenReturn(skill("other", 3));

        PageResult<CollectionDefinition> page = service.page(actor, 1, 12, "", "downloads");

        assertThat(page.items()).extracting(CollectionDefinition::collectionId).containsExactly("featured", "secondary");
        verify(catalog).detail("visible", actor);
        verify(catalog).detail("hidden", actor);
        verify(catalog).detail("other", actor);
        verify(catalog, never()).detail("visible");
        verify(catalog, never()).detail("hidden");
        verify(catalog, never()).detail("other");
    }

    private GovernanceConfigurationView view(CollectionDefinition... collections) {
        return new GovernanceConfigurationView(List.of(), List.of(), List.of(), List.of(),
                List.of(collections), policy());
    }

    private PlatformPolicy policy() {
        return new PlatformPolicy(1, List.of(12, 24, 48), 48, "1.0.0", "public", "admin",
                Instant.parse("2026-08-24T00:00:00Z"));
    }

    private SkillRecord skill(String skillId, int installs) {
        return new SkillRecord(skillId, skillId, "1.0.0", "desc", "other", List.of(), "low", "low",
                "team-a", "owner", "", "blue", "published", "2026-08-24", "2026-08-24",
                "Java", "Java", "", List.of(), List.of(), List.of(), "", "", "", List.of(),
                new SkillMetrics(installs, 0, 0, 0, 0));
    }
}
