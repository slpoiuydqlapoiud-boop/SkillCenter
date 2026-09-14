package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProviderConnectivityProbeSchedulerTest {
    @Test
    void scheduledExecutionDelegatesToTheNonAuditingProbeBoundary() {
        ProviderConnectivityProbeService probe = mock(ProviderConnectivityProbeService.class);
        ProviderConnectivityProbeScheduler scheduler = new ProviderConnectivityProbeScheduler(probe);

        scheduler.probeScheduled();

        verify(probe).probeScheduled();
    }
}
