package com.huawei.skillcenter.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps explicitly enabled external search health evidence fresh without producing user audit noise. */
@Component
@ConditionalOnProperty(name = "skill-center.search-index.probe-scheduler-enabled", havingValue = "true")
public class SkillSearchConnectivityProbeScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillSearchConnectivityProbeScheduler.class);

    private final SkillSearchConnectivityProbeService probeService;

    public SkillSearchConnectivityProbeScheduler(SkillSearchConnectivityProbeService probeService) {
        if (probeService == null) throw new IllegalArgumentException("probeService is required");
        this.probeService = probeService;
    }

    @Scheduled(
            fixedDelayString = "${skill-center.search-index.probe-scheduler-interval-ms:60000}",
            initialDelayString = "${skill-center.search-index.probe-scheduler-initial-delay-ms:1000}")
    public void probeScheduled() {
        try {
            probeService.probeScheduled();
        } catch (RuntimeException exception) {
            LOGGER.warn("scheduled search index probe failed; next pass will retry");
        }
    }
}
