package com.huawei.skillcenter.quality;

import java.time.Duration;

/** Transport boundary that keeps network behavior replaceable and testable. */
public interface ProviderProbeTransport {
    ProviderProbeTransportResult probe(String endpoint, Duration timeout);
}
