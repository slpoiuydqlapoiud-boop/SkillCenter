package com.huawei.skillcenter.persistence;

import com.huawei.skillcenter.governance.GovernanceStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "skill-center.persistence.backend=postgresql",
        "skill-center.governance-backend=postgresql"
})
@Testcontainers(disabledWithoutDocker = true)
class PostgresPersistenceStartupIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("skill-center.persistence.postgresql.url", POSTGRES::getJdbcUrl);
        registry.add("skill-center.persistence.postgresql.username", POSTGRES::getUsername);
        registry.add("skill-center.persistence.postgresql.password", POSTGRES::getPassword);
    }

    @Autowired
    private PostgresPersistenceBackend persistenceBackend;

    @Autowired
    private GovernanceStateRepository governanceStateRepository;

    @Test
    void migratesBeforeJdbcRepositoriesLoad() {
        assertThat(persistenceBackend.status().state()).isEqualTo("READY");
        assertThat(governanceStateRepository.loadOrSeed(
                com.huawei.skillcenter.governance.GovernanceSnapshot::empty).revision()).isZero();
    }
}
