package com.huawei.skillcenter.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillSearchRefreshMessageBusConsumerTest {
    @Test
    void acknowledgesOnlyAfterRefreshSucceeds() {
        SkillSearchRefreshEvent event = new SkillSearchRefreshEvent("skill-a", 7L, "VERSION_PUBLISHED");
        FakeBus bus = new FakeBus(new SkillSearchRefreshEventBus.Delivery("message-1", event));
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
        FakeBus bus = new FakeBus(new SkillSearchRefreshEventBus.Delivery("message-1", event));
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
