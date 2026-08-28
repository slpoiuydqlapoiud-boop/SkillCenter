package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticVersionTest {
    @Test
    void comparesCoreAndPrereleaseIdentifiersUsingSemVerOrdering() {
        assertThat(SemanticVersion.parse("1.0.0-alpha")).isLessThan(SemanticVersion.parse("1.0.0-alpha.1"));
        assertThat(SemanticVersion.parse("1.0.0-alpha.1")).isLessThan(SemanticVersion.parse("1.0.0-beta"));
        assertThat(SemanticVersion.parse("1.0.0-beta")).isLessThan(SemanticVersion.parse("1.0.0"));
        assertThat(SemanticVersion.parse("2.0.0")).isGreaterThan(SemanticVersion.parse("1.99.99"));
    }

    @Test
    void ignoresBuildMetadataAndRejectsLeadingZeroes() {
        assertThat(SemanticVersion.parse("1.2.3+build.7")).isEqualByComparingTo(SemanticVersion.parse("1.2.3"));
        assertThatThrownBy(() -> SemanticVersion.parse("01.2.3")).isInstanceOf(IllegalArgumentException.class);
    }
}
