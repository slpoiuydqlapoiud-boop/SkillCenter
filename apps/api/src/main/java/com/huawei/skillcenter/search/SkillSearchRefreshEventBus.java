package com.huawei.skillcenter.search;

import java.util.List;

/** Optional low-latency transport for metadata-only search refresh events. */
public interface SkillSearchRefreshEventBus {
    void publish(SkillSearchRefreshEvent event);

    List<Delivery> poll(String consumerId, int limit);

    void acknowledge(Delivery delivery);

    record Delivery(String messageId, String consumerId, SkillSearchRefreshEvent event) {
        public Delivery {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId is required");
            }
            if (consumerId == null || consumerId.isBlank()) {
                throw new IllegalArgumentException("consumerId is required");
            }
            if (event == null) {
                throw new IllegalArgumentException("event is required");
            }
            messageId = messageId.trim();
            consumerId = consumerId.trim();
        }
    }
}
