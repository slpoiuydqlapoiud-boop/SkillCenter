package com.huawei.skillcenter.quality;

import java.util.List;

/** Read-only comparison between the reserved external contract and a registered adapter. */
public record ProviderContractVerification(
        String providerId,
        String kind,
        String expectedVersion,
        String actualVersion,
        String activeStatus,
        String verificationStatus,
        List<String> expectedCapabilities,
        List<String> actualCapabilities,
        List<String> missingCapabilities,
        List<String> unexpectedCapabilities,
        String reason
) {
    public ProviderContractVerification {
        expectedCapabilities = stable(expectedCapabilities);
        actualCapabilities = stable(actualCapabilities);
        missingCapabilities = stable(missingCapabilities);
        unexpectedCapabilities = stable(unexpectedCapabilities);
        expectedVersion = expectedVersion == null ? "" : expectedVersion;
        actualVersion = actualVersion == null ? "" : actualVersion;
        activeStatus = activeStatus == null ? "" : activeStatus;
        verificationStatus = verificationStatus == null ? "" : verificationStatus;
        reason = reason == null ? "" : reason;
    }

    private static List<String> stable(List<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }
}
