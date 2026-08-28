package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationSuiteDomainTest {
    @Test
    void acceptsBoundedVersionedSuiteMetadataAndDefensivelyCopiesCases() {
        List<EvaluationCase> cases = new java.util.ArrayList<>(List.of(
                new EvaluationCase("case-1", "成功路径")));

        EvaluationSuiteRequest request = new EvaluationSuiteRequest(
                "release", "Release regression", "release-v2", true, cases);
        cases.clear();

        assertThat(request.id()).isEqualTo("release");
        assertThat(request.version()).isEqualTo("release-v2");
        assertThat(request.cases()).containsExactly(new EvaluationCase("case-1", "成功路径"));
    }

    @Test
    void rejectsUnsafeOrAmbiguousSuiteMetadata() {
        assertThatThrownBy(() -> new EvaluationSuiteRequest(
                "release/v2", "Release", "release-v2", true,
                List.of(new EvaluationCase("case-1", "成功路径"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");

        assertThatThrownBy(() -> new EvaluationSuiteRequest(
                "release", "Release", "release/v2", true,
                List.of(new EvaluationCase("case-1", "成功路径"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");

        assertThatThrownBy(() -> new EvaluationSuiteRequest(
                "release", "Release", "release-v2", true,
                List.of(new EvaluationCase("case-1", "成功路径"),
                        new EvaluationCase("case-1", "重复用例"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsControlCharactersAndOversizedCaseMetadata() {
        assertThatThrownBy(() -> new EvaluationCase("case-1\n", "成功路径"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");

        assertThatThrownBy(() -> new EvaluationCase("case-1", "bad\u0000name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }
}
