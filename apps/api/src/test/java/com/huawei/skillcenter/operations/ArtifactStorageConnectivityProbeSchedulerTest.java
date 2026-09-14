package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ArtifactStorageConnectivityProbeSchedulerTest {
    @Test
    void scheduledExecutionDelegatesToTheNonAuditingProbeBoundary() {
        ArtifactStorageConnectivityProbeService probe = mock(ArtifactStorageConnectivityProbeService.class);
        ArtifactStorageConnectivityProbeScheduler scheduler = new ArtifactStorageConnectivityProbeScheduler(probe);

        scheduler.probeScheduled();

        verify(probe).probeScheduled();
    }
}
