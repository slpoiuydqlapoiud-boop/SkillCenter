package com.huawei.skillcenter.search;

import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/** Delivers message-bus events with ACK-after-success semantics. */
public final class SkillSearchRefreshMessageBusConsumer {
    private final SkillSearchRefreshEventBus bus;
    private final SkillSearchRefreshEventConsumer consumer;
    private final String consumerId;
    private final int batchSize;

    public SkillSearchRefreshMessageBusConsumer(SkillSearchRefreshEventBus bus,
                                                SkillSearchRefreshEventConsumer consumer,
                                                String consumerId,
                                                int batchSize) {
        if (bus == null || consumer == null) {
            throw new IllegalArgumentException("bus and consumer are required");
        }
        this.consumerId = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        this.bus = bus;
        this.consumer = consumer;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${skill-center.search-index-events.bus.poll-interval-ms:1000}")
    public void pollScheduled() {
        try {
            runOnce();
        } catch (RuntimeException ignored) {
            // Leave the current message unacknowledged so the transport retries it.
        }
    }

    void runOnce() {
        List<SkillSearchRefreshEventBus.Delivery> deliveries = bus.poll(consumerId, batchSize);
        if (deliveries == null) {
            throw new IllegalStateException("message bus returned no delivery list");
        }
        for (SkillSearchRefreshEventBus.Delivery delivery : deliveries) {
            if (delivery == null) {
                throw new IllegalStateException("message bus returned a null delivery");
            }
            consumer.onRefresh(delivery.event());
            bus.acknowledge(delivery);
        }
    }
}
