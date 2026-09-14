package com.huawei.skillcenter.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps explicitly enabled artifact-storage connectivity evidence fresh without audit noise. */
@Component
@ConditionalOnProperty(name = "skill-center.artifact-storage.probe-scheduler-enabled", havingValue = "true")
public class ArtifactStorageConnectivityProbeScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactStorageConnectivityProbeScheduler.class);

    private final ArtifactStorageConnectivityProbeService probeService;

    public ArtifactStorageConnectivityProbeScheduler(ArtifactStorageConnectivityProbeService probeService) {
        if (probeService == null) throw new IllegalArgumentException("probeService is required");
        this.probeService = probeService;
    }

    @Scheduled(
            fixedDelayString = "${skill-center.artifact-storage.probe-scheduler-interval-ms:60000}",
            initialDelayString = "${skill-center.artifact-storage.probe-scheduler-initial-delay-ms:1000}")
    public void probeScheduled() {
        try {
            probeService.probeScheduled();
        } catch (RuntimeException exception) {
            LOGGER.warn("scheduled artifact storage probe failed; next pass will retry");
        }
    }
}
