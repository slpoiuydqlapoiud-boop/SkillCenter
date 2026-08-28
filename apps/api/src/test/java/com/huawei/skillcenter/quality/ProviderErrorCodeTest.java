package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderErrorCodeTest {
    @Test
    void keepsOnlyBoundedStableErrorCodeIdentifiers() {
        assertThat(ProviderErrorCodes.normalize(" UPSTREAM_TIMEOUT ", "FALLBACK"))
                .isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(ProviderErrorCodes.normalize("customer prompt: do-not-store", "FALLBACK"))
                .isEqualTo("FALLBACK");
        assertThat(ProviderErrorCodes.normalize("", "FALLBACK"))
                .isEqualTo("FALLBACK");
        assertThat(ProviderErrorCodes.normalize("A", "FALLBACK"))
                .isEqualTo("FALLBACK");
    }

    @Test
    void neverReturnsAnUnboundedFallbackOrBusinessText() {
        String fallback = "RUNNER_EXECUTION_FAILED";
        String longValue = "A".repeat(64);

        assertThat(ProviderErrorCodes.normalize(longValue, fallback)).isEqualTo(fallback);
        assertThat(ProviderErrorCodes.normalize(null, fallback)).isEqualTo(fallback);
        assertThat(ProviderErrorCodes.normalize("customer data: secret", fallback))
                .doesNotContain("customer")
                .isEqualTo(fallback);
    }
}
