package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityGateServiceTest {
    private final QualityGateService service = new QualityGateService();

    @Test
    void passesWhenDynamicAndStaticThresholdsAreMet() {
        QualityGate gate = service.evaluate(new QualityRuleSet("default", "quality-v1", 80, 0.8, 90),
                100, 1.0, 100);

        assertThat(gate.status()).isEqualTo(QualityGateStatus.PASSED);
        assertThat(gate.reasons()).isEmpty();
    }

    @Test
    void blocksAndExplainsEveryThresholdViolation() {
        QualityGate gate = service.evaluate(new QualityRuleSet("default", "quality-v1", 80, 0.8, 90),
                50, 0.5, 70);

        assertThat(gate.status()).isEqualTo(QualityGateStatus.BLOCKED);
        assertThat(gate.reasons()).containsExactly(
                "QUALITY_SCORE_BELOW_THRESHOLD",
                "PASS_RATE_BELOW_THRESHOLD",
                "STATIC_SCORE_BELOW_THRESHOLD");
    }
}
