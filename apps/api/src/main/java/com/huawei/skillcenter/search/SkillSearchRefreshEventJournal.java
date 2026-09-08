package com.huawei.skillcenter.search;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Captures local refresh events for later delivery to other instances. */
@Component
@Conditional(SkillSearchRefreshEventCondition.PostgresqlPersistence.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "skill-center.search-index-events.enabled", havingValue = "true")
public final class SkillSearchRefreshEventJournal {
    private final SkillSearchRefreshEventStore store;
    private final SkillSearchRefreshEventBus bus;

    public SkillSearchRefreshEventJournal(SkillSearchRefreshEventStore store) {
        this(store, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillSearchRefreshEventJournal(SkillSearchRefreshEventStore store,
                                          ObjectProvider<SkillSearchRefreshEventBus> busProvider) {
        if (store == null) throw new IllegalArgumentException("store is required");
        this.store = store;
        this.bus = busProvider == null ? null : busProvider.getIfAvailable();
    }

    @EventListener
    public void append(SkillSearchRefreshEvent event) {
        try {
            store.append(event);
            if (bus != null) bus.publish(event);
        } catch (RuntimeException ignored) {
            // The primary governance write already succeeded; polling/readiness exposes the gap.
        }
    }
}
