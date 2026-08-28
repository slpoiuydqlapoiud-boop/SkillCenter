package com.huawei.skillcenter.search;

import java.util.List;

/** Durable, at-least-once journal boundary for cross-instance search refreshes. */
public interface SkillSearchRefreshEventStore {
    void append(SkillSearchRefreshEvent event);

    List<StoredSkillSearchRefreshEvent> findAfter(long sequence, int limit);

    record StoredSkillSearchRefreshEvent(long sequence, SkillSearchRefreshEvent event) {
        public StoredSkillSearchRefreshEvent {
            if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
            if (event == null) throw new IllegalArgumentException("event is required");
        }
    }
}
