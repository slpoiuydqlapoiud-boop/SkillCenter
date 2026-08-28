package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.EnvironmentProviderCredentialResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration
public class OrganizationDirectoryConfiguration {
    @Bean
    OrganizationDirectoryStore organizationDirectoryStore(ObjectMapper objectMapper,
                                                            OrganizationDirectoryProperties properties) {
        return new OrganizationDirectoryStore(java.nio.file.Path.of(properties.getStorage()), objectMapper,
                Clock.systemUTC());
    }

    @Bean
    OrganizationDirectoryClient organizationDirectoryClient(OrganizationDirectoryProperties properties,
                                                            ObjectMapper objectMapper) {
        EnvironmentProviderCredentialResolver credentials = new EnvironmentProviderCredentialResolver();
        return new HttpOrganizationDirectoryClient(properties, credentials::resolve,
                HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(properties.getConnectTimeoutMs()))
                        .build(), objectMapper, Clock.systemUTC());
    }
}
