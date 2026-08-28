package com.huawei.skillcenter.lifecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillLifecycleProjectionFreshnessPolicyTest {
    @Test
    void defaultsKeepProjectionFreshnessBoundedToFifteenMinutes() {
        assertThat(SkillLifecycleProjectionFreshnessPolicy.defaults().maxProjectionAgeSeconds())
                .isEqualTo(900L);
    }

    @Test
    void rejectsUnboundedFreshnessConfiguration() {
        assertThatThrownBy(() -> new SkillLifecycleProjectionFreshnessPolicy(59L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillLifecycleProjectionFreshnessPolicy(86_401L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
