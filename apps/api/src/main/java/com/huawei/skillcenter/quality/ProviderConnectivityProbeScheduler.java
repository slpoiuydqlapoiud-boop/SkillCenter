package com.huawei.skillcenter.quality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps explicitly enabled external-provider connectivity evidence fresh without audit noise. */
@Component
@ConditionalOnProperty(name = "skill-center.providers.probe-scheduler-enabled", havingValue = "true")
public class ProviderConnectivityProbeScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderConnectivityProbeScheduler.class);

    private final ProviderConnectivityProbeService probeService;

    public ProviderConnectivityProbeScheduler(ProviderConnectivityProbeService probeService) {
        if (probeService == null) throw new IllegalArgumentException("probeService is required");
        this.probeService = probeService;
    }

    @Scheduled(
            fixedDelayString = "${skill-center.providers.probe-scheduler-interval-ms:60000}",
            initialDelayString = "${skill-center.providers.probe-scheduler-initial-delay-ms:1000}")
    public void probeScheduled() {
        try {
            probeService.probeScheduled();
        } catch (RuntimeException exception) {
            LOGGER.warn("scheduled provider connectivity probe failed; next pass will retry");
        }
    }
}
