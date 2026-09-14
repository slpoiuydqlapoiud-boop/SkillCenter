package com.huawei.skillcenter.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps explicitly enabled release-target connectivity evidence fresh without audit noise. */
@Component
@ConditionalOnProperty(name = "skill-center.release-target.probe-scheduler-enabled", havingValue = "true")
public class ReleaseTargetConnectivityProbeScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseTargetConnectivityProbeScheduler.class);

    private final ReleaseTargetConnectivityProbeService probeService;

    public ReleaseTargetConnectivityProbeScheduler(ReleaseTargetConnectivityProbeService probeService) {
        if (probeService == null) throw new IllegalArgumentException("probeService is required");
        this.probeService = probeService;
    }

    @Scheduled(
            fixedDelayString = "${skill-center.release-target.probe-scheduler-interval-ms:60000}",
            initialDelayString = "${skill-center.release-target.probe-scheduler-initial-delay-ms:1000}")
    public void probeScheduled() {
        try {
            probeService.probeScheduled();
        } catch (RuntimeException exception) {
            LOGGER.warn("scheduled release target probe failed; next pass will retry");
        }
    }
}
