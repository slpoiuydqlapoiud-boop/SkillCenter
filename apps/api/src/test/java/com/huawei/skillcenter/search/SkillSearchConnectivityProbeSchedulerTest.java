package com.huawei.skillcenter.search;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkillSearchConnectivityProbeSchedulerTest {
    @Test
    void scheduledExecutionDelegatesToTheNonAuditingProbeBoundary() {
        SkillSearchConnectivityProbeService probe = mock(SkillSearchConnectivityProbeService.class);
        SkillSearchConnectivityProbeScheduler scheduler = new SkillSearchConnectivityProbeScheduler(probe);

        scheduler.probeScheduled();

        verify(probe).probeScheduled();
    }
}
