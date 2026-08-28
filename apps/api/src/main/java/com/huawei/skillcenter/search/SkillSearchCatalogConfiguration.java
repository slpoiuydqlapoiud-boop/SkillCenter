package com.huawei.skillcenter.search;

import com.huawei.skillcenter.access.SkillScopeRepository;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.skill.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SkillSearchCatalogConfiguration {
    @Bean
    @Conditional(SkillSearchBackendCondition.Json.class)
    JsonSkillSearchIndex skillSearchIndex() {
        return new JsonSkillSearchIndex();
    }

    @Bean
    @Conditional(SkillSearchBackendCondition.Postgresql.class)
    JdbcSkillSearchIndex postgresSkillSearchIndex(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                                   ObjectMapper mapper,
                                                   org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new JdbcSkillSearchIndex(jdbc, mapper, transactionManager);
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
