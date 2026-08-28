package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaticQualityEvaluatorTest {
    private final StaticQualityEvaluator evaluator = new StaticQualityEvaluator();

    @Test
    void reportsTraceableStaticChecksForSkillIdentity() {
        StaticQualityReport report = evaluator.evaluate("eox-query", "1.2.0");

        assertThat(report.score()).isEqualTo(100);
        assertThat(report.totalChecks()).isEqualTo(2);
        assertThat(report.passedChecks()).isEqualTo(2);
        assertThat(report.checks()).allMatch(StaticQualityCheck::passed);
    }

    @Test
    void flagsInvalidSemanticVersion() {
        StaticQualityReport report = evaluator.evaluate("eox-query", "latest");

        assertThat(report.score()).isEqualTo(50);
        assertThat(report.checks()).anyMatch(check -> !check.passed() && "VERSION_SEMVER".equals(check.ruleId()));
    }
}
