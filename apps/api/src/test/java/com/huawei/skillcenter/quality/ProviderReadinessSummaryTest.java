package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderReadinessSummaryTest {
    @Test
    void normalizesProviderIdListsToStableDistinctOrder() {
        ProviderReadinessSummary summary = new ProviderReadinessSummary("PARTIAL", 3, 3, 3, 0,
                "EXTERNAL_PROVIDERS_CONTRACT_ONLY", null,
                List.of(" langfuse-observability", "openclaw-runner", "langfuse-observability", ""),
                List.of("openclaw-runner", " deepeval-evaluation", "openclaw-runner"));

        assertThat(summary.contractOnlyProviderIds())
                .containsExactly("langfuse-observability", "openclaw-runner");
        assertThat(summary.notConfiguredProviderIds())
                .containsExactly("deepeval-evaluation", "openclaw-runner");
    }
}
