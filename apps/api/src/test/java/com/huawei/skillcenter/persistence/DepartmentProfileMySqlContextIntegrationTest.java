package com.huawei.skillcenter.persistence;

import com.huawei.skillcenter.access.MysqlSkillScopeStore;
import com.huawei.skillcenter.governance.LocalAuthenticationService;
import com.huawei.skillcenter.governance.MysqlGovernanceStateRepository;
import com.huawei.skillcenter.quality.MysqlBenchmarkStore;
import com.huawei.skillcenter.quality.MysqlOptimizationExperimentStore;
import com.huawei.skillcenter.quality.MysqlOptimizationWorkItemStore;
import com.huawei.skillcenter.quality.MysqlQualityEvidenceStore;
import com.huawei.skillcenter.release.MysqlReleaseRecordStore;
import com.huawei.skillcenter.relationship.MysqlSkillRelationStore;
import com.huawei.skillcenter.execution.MysqlExecutionEnvironmentStore;
import com.huawei.skillcenter.operations.MysqlProductionEvidenceStore;
import com.huawei.skillcenter.search.JsonSkillSearchIndex;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the complete department selector combination against a real MySQL schema. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "skill-center.persistence.backend=mysql",
        "skill-center.governance-backend=mysql",
        "skill-center.quality-evidence-backend=mysql",
        "skill-center.benchmark-backend=mysql",
        "skill-center.release-backend=mysql",
        "skill-center.optimization-work-item-backend=mysql",
        "skill-center.optimization-experiment-backend=mysql",
        "skill-center.production-evidence-backend=mysql",
        "skill-center.execution-environment-backend=mysql",
        "skill-center.skill-scope-backend=mysql",
        "skill-center.skill-relation-backend=mysql",
        "skill-center.search-index-backend=json",
        "skill-center.lifecycle-projection.backend=json",
        "skill-center.runtime-summary-backend=memory",
        "skill-center.artifact-storage-backend=local",
        "skill-center.package-upload-backend=local",
        "skill-center.search-index-events.enabled=false",
        "skill-center.security.authentication.mode=local",
        "skill-center.security.authentication.local.require-token=true",
        "skill-center.security.authentication.local.allow-guest=true",
        "skill-center.security.authentication.local.password=local-only",
        "skill-center.providers.runner=mock",
        "skill-center.providers.evaluation=mock",
        "skill-center.providers.observability=mock",
        "skill-center.providers.trace=mock"
})
class DepartmentProfileMySqlContextIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("skillcenter")
            .withUsername("skillcenter")
            .withPassword("local-only");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("skill-center.persistence.mysql.url", MYSQL::getJdbcUrl);
        registry.add("skill-center.persistence.mysql.username", MYSQL::getUsername);
        registry.add("skill-center.persistence.mysql.password", MYSQL::getPassword);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Flyway flyway;

    @Test
    void bootsAllDepartmentBackendsAgainstMysql() {
        assertThat(context.getBean(MysqlPersistenceBackend.class)).isNotNull();
        assertThat(context.getBean(MysqlGovernanceStateRepository.class)).isNotNull();
        assertThat(context.getBean(MysqlQualityEvidenceStore.class)).isNotNull();
        assertThat(context.getBean(MysqlBenchmarkStore.class)).isNotNull();
        assertThat(context.getBean(MysqlReleaseRecordStore.class)).isNotNull();
        assertThat(context.getBean(MysqlOptimizationWorkItemStore.class)).isNotNull();
        assertThat(context.getBean(MysqlOptimizationExperimentStore.class)).isNotNull();
        assertThat(context.getBean(MysqlExecutionEnvironmentStore.class)).isNotNull();
        assertThat(context.getBean(MysqlSkillScopeStore.class)).isNotNull();
        assertThat(context.getBean(MysqlSkillRelationStore.class)).isNotNull();
        assertThat(context.getBean(MysqlProductionEvidenceStore.class)).isNotNull();
        assertThat(context.getBean(JsonSkillSearchIndex.class)).isNotNull();
        assertThat(context.getBean(LocalAuthenticationService.class)).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(context.getBeansOfType(com.huawei.skillcenter.persistence.PostgresPersistenceConfiguration.class))
                .isEmpty();
    }
}
