package com.huawei.skillcenter.search;

import com.huawei.skillcenter.access.SkillScopeRepository;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.skill.SkillRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SkillSearchCatalogConfiguration {
    @Bean
    SkillSearchIndex skillSearchIndex() {
        return new JsonSkillSearchIndex();
    }

    @Bean
    SkillSearchDocumentSource skillSearchDocumentSource(GovernanceStore store, SkillRepository repository,
                                                         SkillScopeRepository scopes) {
        return new GovernedSkillSearchDocumentSource(store, repository,
                skillId -> scopes.find(skillId).map(scope -> new SkillSearchScope(scope.skillId(),
                        scope.visibility().name(), scope.ownerTeamId())),
                () -> store.snapshot().audits().size());
    }

    @Bean
    SkillSearchRefreshCoordinator skillSearchRefreshCoordinator(SkillSearchIndex index,
                                                                 SkillSearchDocumentSource source) {
        return new SkillSearchRefreshCoordinator(index, source);
    }
}
