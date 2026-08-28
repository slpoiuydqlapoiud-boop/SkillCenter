package com.huawei.skillcenter.operations;

import java.util.List;

public interface TraceProvider {
    String providerId();

    String providerVersion();

    default List<String> capabilities() {
        return List.of("trace-metadata", "failure-location");
    }

    List<TraceObservation> query(TraceQuery query);
}
