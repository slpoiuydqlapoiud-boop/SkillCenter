package com.huawei.skillcenter.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenSearchSkillSearchIndexIntegrationTest {
    private GenericContainer<?> opensearch;
    private boolean dockerAvailable;

    @BeforeAll
    void startOpenSearch() {
        dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        if (!dockerAvailable) return;
        opensearch = new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:2.17.1"))
                .withEnv("discovery.type", "single-node")
                .withEnv("plugins.security.disabled", "true")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/_cluster/health")
                        .forPort(9200)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        opensearch.start();
    }

    @AfterAll
    void stopOpenSearch() {
        if (opensearch != null) opensearch.stop();
    }

    @Test
    void probesBulkIndexesAndQueriesSkillMetadataAgainstRealOpenSearch() {
        requireDocker();
        HttpSkillSearchIndex index = new HttpSkillSearchIndex(endpoint(), "skillcenter-integration-v1",
                "secret://search", Duration.ofSeconds(5), Duration.ofSeconds(15), 256_000,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper().findAndRegisterModules(), reference -> "local-integration-token",
                java.time.Clock.systemUTC());

        assertThat(index.probe().status()).isEqualTo("REACHABLE");
        SkillSearchDocument document = new SkillSearchDocument("integration-skill", "Lifecycle Search",
                "Skill lifecycle integration test", List.of("lifecycle", "search"), "platform", "governance",
                "published", "low", Instant.parse("2026-08-28T00:00:00Z"),
                Instant.parse("2026-08-28T00:00:00Z"), "1.0.0", "PUBLIC", "");

        SkillSearchRebuildResult rebuild = index.rebuild(List.of(document), "integration-hash-v1");

        assertThat(rebuild.documentCount()).isEqualTo(1);
        assertThat(index.status().state()).isEqualTo("READY");
        assertThat(index.search(new SkillSearchQuery("Lifecycle Search", "", "published", "low", "updated")))
                .extracting(SkillSearchHit::skillId)
                .containsExactly("integration-skill");
    }

    private String endpoint() {
        return "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200);
    }

    private void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable,
                "CAPABILITY_SKIP: Docker is unavailable; OpenSearch Testcontainers integration cannot run");
    }
}
