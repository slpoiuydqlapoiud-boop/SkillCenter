package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationExperimentObservationDomainTest {
    @Test
    void countersCannotExceedTotalCalls() {
        assertThatThrownBy(() -> new OptimizationExperimentObservation(
                "observation-1", "experiment-1", "skill-a", "1.1.0", "production", "", "", "", "24h",
                null, "admin", 2, 2, 1, 0, 0, 100, 20, "CAPTURED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counters");
    }

    @Test
    void emptyWindowIsExplicitlyMarkedNoTraffic() {
        assertThatThrownBy(() -> new OptimizationExperimentObservation(
                "observation-1", "experiment-1", "skill-a", "1.1.0", "production", "", "", "", "24h",
                null, "admin", 0, 0, 0, 0, 0, 0, 0, "CAPTURED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_TRAFFIC");
    }
}
