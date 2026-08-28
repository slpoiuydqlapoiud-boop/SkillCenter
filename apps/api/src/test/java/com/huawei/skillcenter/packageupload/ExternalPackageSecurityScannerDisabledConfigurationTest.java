package com.huawei.skillcenter.packageupload;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "skill-center.package-security.external.mode=disabled")
class ExternalPackageSecurityScannerDisabledConfigurationTest {
    @Autowired
    private ExternalPackageSecurityScanner scanner;

    @Test
    void keepsContractOnlyScannerByDefault() {
        assertThat(scanner).isInstanceOf(ContractOnlyExternalPackageSecurityScanner.class);
    }
}
