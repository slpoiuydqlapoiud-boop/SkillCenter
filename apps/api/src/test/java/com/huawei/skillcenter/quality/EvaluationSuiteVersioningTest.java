package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationSuiteVersioningTest {
    @Test
    void keepsImmutableVersionsAndMovesTheEnabledPointerAtomically() {
        QualityEvaluationService service = service();
        service.createSuite(suite("release-v1", true));
        service.createSuite(suite("release-v2", true));

        assertThat(service.listSuites().stream().filter(item -> item.id().equals("release")).toList())
                .extracting(EvaluationSuite::version)
                .containsExactly("release-v1", "release-v2");
        assertThat(service.resolveSuite("release", "release-v1").enabled()).isFalse();
        assertThat(service.resolveSuite("release", "release-v2").enabled()).isTrue();
        assertThat(service.resolveSuite("release", "").version()).isEqualTo("release-v2");
    }

    @Test
    void rejectsDuplicateVersionAndRequiresEnabledVersionForNewEvaluation() {
        QualityEvaluationService service = service();
        service.createSuite(suite("release-v1", true));

        assertThatThrownBy(() -> service.createSuite(suite("release-v1", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");

        service.createSuite(suite("release-v2", true));
        assertThatThrownBy(() -> service.submit(new EvaluationRequest(
                "skill", "1.0.0", "release", "release-v1", "success", 1_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled");

        EvaluationRun pinned = service.submit(new EvaluationRequest(
                "skill", "1.0.0", "release", "release-v2", "success", 1_000));
        assertThat(pinned.suiteVersion()).isEqualTo("release-v2");
    }

    private static EvaluationSuiteRequest suite(String version, boolean enabled) {
        return new EvaluationSuiteRequest(
                "release", "Release regression", version, enabled,
                List.of(new EvaluationCase("case-1", version + " case")));
    }

    private static QualityEvaluationService service() {
        return new QualityEvaluationService(new MockRunner(), new MockEvaluationProvider(),
                Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
    }
}
