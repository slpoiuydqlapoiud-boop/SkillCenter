package com.huawei.skillcenter.search;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillSearchRefreshMessageBusConsumerTest {
    @Test
    void acknowledgesOnlyAfterRefreshSucceeds() {
        SkillSearchRefreshEvent event = new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_PUBLISHED");
        FakeBus bus = new FakeBus(new SkillSearchRefreshEventBus.Delivery("message-1", "consumer-a", event));
        List<SkillSearchRefreshEvent> received = new ArrayList<>();
        SkillSearchRefreshMessageBusConsumer consumer = new SkillSearchRefreshMessageBusConsumer(
                bus, received::add, "consumer-a", 10);

        consumer.runOnce();

        assertThat(received).containsExactly(event);
        assertThat(bus.acknowledged).containsExactly("message-1");
    }

    @Test
    void failedRefreshLeavesMessageUnacknowledgedForRetry() {
        SkillSearchRefreshEvent event = new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_PUBLISHED");
        FakeBus bus = new FakeBus(new SkillSearchRefreshEventBus.Delivery("message-1", "consumer-a", event));
        SkillSearchRefreshMessageBusConsumer consumer = new SkillSearchRefreshMessageBusConsumer(
                bus, ignored -> { throw new IllegalStateException("refresh failed"); }, "consumer-a", 10);

        assertThatThrownBy(consumer::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("refresh failed");
        assertThat(bus.acknowledged).isEmpty();
    }

    @Test
    void validatesConsumerConfiguration() {
        FakeBus bus = new FakeBus();

        assertThatThrownBy(() -> new SkillSearchRefreshMessageBusConsumer(bus, ignored -> { }, "", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillSearchRefreshMessageBusConsumer(bus, ignored -> { }, "consumer-a", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillSearchRefreshEventBus.Delivery("message-1", "", new SkillSearchRefreshEvent(
                "skill-a", 7L, "VERSION_PUBLISHED")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deliveryCarriesConsumerIdentityForAckRouting() {
        SkillSearchRefreshEvent event = new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_PUBLISHED");
        SkillSearchRefreshEventBus.Delivery delivery =
                new SkillSearchRefreshEventBus.Delivery("message-1", "consumer-a", event);

        assertThat(delivery.consumerId()).isEqualTo("consumer-a");
    }

    @Test
    void derivesAnIndependentStableGroupForEachConsumerIdentity() {
        RedisSkillSearchRefreshEventBus bus = new RedisSkillSearchRefreshEventBus(
                new StringRedisTemplate(), "skill-center:search:refresh", "skill-center-search-index");

        String first = bus.consumerGroupFor("consumer-a");
        String second = bus.consumerGroupFor("consumer-b");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isEqualTo(bus.consumerGroupFor("consumer-a"));
        assertThat(first).startsWith("skill-center-search-index-");
        assertThat(first).hasSizeLessThanOrEqualTo(128);
    }

    private static final class FakeBus implements SkillSearchRefreshEventBus {
        private final List<Delivery> deliveries;
        private final List<String> acknowledged = new ArrayList<>();

        private FakeBus(Delivery... deliveries) {
            this.deliveries = new ArrayList<>(List.of(deliveries));
        }

        @Override
        public void publish(SkillSearchRefreshEvent event) {
        }

        @Override
        public List<Delivery> poll(String consumerId, int limit) {
            return List.copyOf(deliveries);
        }

        @Override
        public void acknowledge(Delivery delivery) {
            acknowledged.add(delivery.messageId());
        }
    }
}
