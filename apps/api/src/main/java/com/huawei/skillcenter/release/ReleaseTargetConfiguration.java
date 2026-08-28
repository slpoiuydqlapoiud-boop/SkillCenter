package com.huawei.skillcenter.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.ProviderCredentialResolver;
import com.huawei.skillcenter.quality.ProviderHttpTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Selects the local Mock target or an explicitly enabled HTTP target. */
@Configuration
public class ReleaseTargetConfiguration {
    @Bean
    @ConditionalOnProperty(name = "skill-center.release-target.mode", havingValue = "http")
    ReleaseTarget httpReleaseTarget(
            @Value("${skill-center.release-target.endpoint:}") String endpoint,
            @Value("${skill-center.release-target.credential-ref:}") String credentialRef,
            @Value("${skill-center.release-target.timeout-ms:10000}") long timeoutMs,
            ProviderHttpTransport transport, ProviderCredentialResolver credentials, ObjectMapper mapper) {
        return new HttpReleaseTarget(new HttpReleaseTargetConfig(endpoint, credentialRef,
                Duration.ofMillis(timeoutMs)), transport, credentials, mapper);
    }
}
