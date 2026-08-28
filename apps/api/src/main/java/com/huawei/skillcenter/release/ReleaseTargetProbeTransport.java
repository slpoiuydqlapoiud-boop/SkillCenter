package com.huawei.skillcenter.release;

import com.huawei.skillcenter.quality.ProviderProbeTransportResult;

import java.time.Duration;

/** Status-only network boundary for a release target health probe. */
@FunctionalInterface
public interface ReleaseTargetProbeTransport {
    ProviderProbeTransportResult probe(String endpoint, String bearerCredential, Duration timeout);
}
