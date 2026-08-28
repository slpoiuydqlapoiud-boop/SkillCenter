package com.huawei.skillcenter.packageupload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.EnvironmentProviderCredentialResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/** Selects the explicit external scanner adapter while preserving the safe default. */
@Configuration
public class ExternalPackageSecurityScannerConfiguration {
    @Bean
    ExternalPackageSecurityScanner externalPackageSecurityScanner(
            ExternalPackageSecurityScannerProperties properties, ObjectMapper objectMapper) {
        if (!properties.required()) return new ContractOnlyExternalPackageSecurityScanner();
        return new HttpExternalPackageSecurityScanner(
                properties,
                new EnvironmentProviderCredentialResolver(),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                        .build(),
                objectMapper);
    }
}
