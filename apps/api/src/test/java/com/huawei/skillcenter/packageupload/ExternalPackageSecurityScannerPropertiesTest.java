package com.huawei.skillcenter.packageupload;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalPackageSecurityScannerPropertiesTest {
    @Test
    void disabledModeIsValidWithoutAnEndpointOrCredential() {
        ExternalPackageSecurityScannerProperties properties = new ExternalPackageSecurityScannerProperties();

        properties.validate();
    }

    @Test
    void requiredModeNeedsSafeEndpointCredentialAndAllDeclaredCapabilities() {
        ExternalPackageSecurityScannerProperties properties = required("https://scanner.example.internal/scan");

        properties.validate();
    }

    @Test
    void loopbackHttpIsAllowedForLocalScannerContractTests() {
        ExternalPackageSecurityScannerProperties properties = required("http://127.0.0.1:18080/scan");

        properties.validate();
    }

    @Test
    void requiredModeRejectsEndpointQueryAndInvalidCredentialReference() {
        ExternalPackageSecurityScannerProperties query = required("https://scanner.example.internal/scan?token=bad");
        ExternalPackageSecurityScannerProperties credential = required("https://scanner.example.internal/scan");
        credential.setCredentialRef("token-value");

        assertThatThrownBy(query::validate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(credential::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiredModeRejectsOutOfRangeLimits() {
        ExternalPackageSecurityScannerProperties properties = required("https://scanner.example.internal/scan");
        properties.setRequestTimeoutMs(99);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    private ExternalPackageSecurityScannerProperties required(String endpoint) {
        ExternalPackageSecurityScannerProperties properties = new ExternalPackageSecurityScannerProperties();
        properties.setMode("required");
        properties.setEndpoint(endpoint);
        properties.setCredentialRef("secret://env/SCANNER_TOKEN");
        properties.setCapabilities(Set.of("MALWARE", "SENSITIVE_INFORMATION",
                "DEPENDENCY_VULNERABILITY", "LICENSE"));
        return properties;
    }
}
