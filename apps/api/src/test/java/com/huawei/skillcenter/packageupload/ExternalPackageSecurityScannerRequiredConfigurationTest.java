package com.huawei.skillcenter.packageupload;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "skill-center.package-security.external.mode=required",
        "skill-center.package-security.external.endpoint=https://scanner.example.internal/scan",
        "skill-center.package-security.external.credential-ref=secret://env/SCANNER_TOKEN",
        "skill-center.package-security.external.capabilities=MALWARE,SENSITIVE_INFORMATION,DEPENDENCY_VULNERABILITY,LICENSE"
})
class ExternalPackageSecurityScannerRequiredConfigurationTest {
    @Autowired
    private ExternalPackageSecurityScanner scanner;

    @Test
    void selectsHttpScannerOnlyWhenExplicitlyRequired() {
        assertThat(scanner).isInstanceOf(HttpExternalPackageSecurityScanner.class);
    }
}
