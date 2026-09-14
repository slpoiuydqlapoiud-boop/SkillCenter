package com.huawei.skillcenter.search;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisSkillSearchRefreshEventBusIntegrationTest {
    private GenericContainer<?> redis;
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate template;
    private boolean dockerAvailable;

    @BeforeAll
    void startRedis() {
        dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        if (!dockerAvailable) return;

        redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        redis.start();

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    void stopRedis() {
        if (connectionFactory != null) connectionFactory.destroy();
        if (redis != null) redis.stop();
    }

    @Test
    void deliversOneRefreshEventToEachIndependentConsumerGroup() {
        requireDocker();
        String stream = "skillcenter:test:search-refresh:" + UUID.randomUUID();
        RedisSkillSearchRefreshEventBus bus = new RedisSkillSearchRefreshEventBus(
                template, stream, "skillcenter-test-search-index");
        SkillSearchRefreshEvent event = new SkillSearchRefreshEvent(
                "integration-skill", 11L, "VERSION_PUBLISHED");

        bus.publish(event);

        List<SkillSearchRefreshEventBus.Delivery> first = bus.poll("api-instance-a", 10);
        List<SkillSearchRefreshEventBus.Delivery> second = bus.poll("api-instance-b", 10);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).event()).isEqualTo(event);
        assertThat(first.get(0).consumerId()).isEqualTo("api-instance-a");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).event()).isEqualTo(event);
        assertThat(second.get(0).consumerId()).isEqualTo("api-instance-b");
        assertThat(bus.consumerGroupFor("api-instance-a"))
                .isNotEqualTo(bus.consumerGroupFor("api-instance-b"));

        bus.acknowledge(first.get(0));
        bus.acknowledge(second.get(0));

        assertThat(bus.poll("api-instance-a", 10)).isEmpty();
        assertThat(bus.poll("api-instance-b", 10)).isEmpty();
        template.delete(stream);
    }

    private void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable,
                "CAPABILITY_SKIP: Docker is unavailable; Redis Testcontainers integration cannot run");
    }
}
