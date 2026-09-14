package com.huawei.skillcenter.release;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReleaseTargetConnectivityProbeSchedulerTest {
    @Test
    void scheduledExecutionDelegatesToTheNonAuditingProbeBoundary() {
        ReleaseTargetConnectivityProbeService probe = mock(ReleaseTargetConnectivityProbeService.class);
        ReleaseTargetConnectivityProbeScheduler scheduler = new ReleaseTargetConnectivityProbeScheduler(probe);

        scheduler.probeScheduled();

        verify(probe).probeScheduled();
    }
}
