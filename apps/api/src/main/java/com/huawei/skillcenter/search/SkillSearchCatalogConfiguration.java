package com.huawei.skillcenter.search;

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
    SkillSearchDocumentSource skillSearchDocumentSource(GovernanceStore store, SkillRepository repository) {
        return new GovernedSkillSearchDocumentSource(store, repository);
    }

    @Bean
    SkillSearchRefreshCoordinator skillSearchRefreshCoordinator(SkillSearchIndex index,
                                                                 SkillSearchDocumentSource source) {
        return new SkillSearchRefreshCoordinator(index, source);
    }
}
