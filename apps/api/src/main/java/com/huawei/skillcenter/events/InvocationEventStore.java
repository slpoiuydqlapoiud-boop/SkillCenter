package com.huawei.skillcenter.events;

import java.time.Instant;
import java.util.Collection;

public interface InvocationEventStore {
    Collection<InvocationEvent> events();

    InvocationEventService.IngestResult putIfAbsent(InvocationEvent event);

    int deleteBefore(Instant cutoff);

    long countBefore(Instant cutoff);
}
