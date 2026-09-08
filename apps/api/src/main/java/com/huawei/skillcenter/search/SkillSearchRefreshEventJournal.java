package com.huawei.skillcenter.search;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Captures local refresh events for later delivery to other instances. */
@Component
@Conditional(SkillSearchRefreshEventCondition.PostgresqlPersistence.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "skill-center.search-index-events.enabled", havingValue = "true")
public final class SkillSearchRefreshEventJournal {
    private final SkillSearchRefreshEventStore store;

    public SkillSearchRefreshEventJournal(SkillSearchRefreshEventStore store) {
        if (store == null) throw new IllegalArgumentException("store is required");
        this.store = store;
    }

    @EventListener
    public void append(SkillSearchRefreshEvent event) {
        try {
            store.append(event);
        } catch (RuntimeException ignored) {
            // The primary governance write already succeeded; polling/readiness exposes the gap.
        }
    }
}
