package com.huawei.skillcenter.distribution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;

/** Selects an explicitly configured artifact backend without implicit fallback. */
@Configuration
public class ArtifactStorageBackendConfiguration {
    @Bean
    @ConditionalOnProperty(name = "skill-center.artifact-storage-backend", havingValue = "object-storage")
    ArtifactStorage objectStorageArtifactStorage(
            @Value("${skill-center.artifact-storage.mode:contract}") String mode,
            @Value("${skill-center.artifact-storage.endpoint:}") String endpoint,
            @Value("${skill-center.artifact-storage.bucket:}") String bucket,
            @Value("${skill-center.artifact-storage.region:us-east-1}") String region,
            @Value("${skill-center.artifact-storage.prefix:skill-packages}") String prefix,
            @Value("${skill-center.artifact-storage.access-key-id-ref:}") String accessKeyIdRef,
            @Value("${skill-center.artifact-storage.secret-access-key-ref:}") String secretAccessKeyRef,
            ObjectStorageCredentialResolver credentials) {
        String normalizedMode = mode == null ? "" : mode.trim();
        if (!"http".equalsIgnoreCase(normalizedMode) && !"contract".equalsIgnoreCase(normalizedMode)) {
            throw new IllegalArgumentException("artifact storage mode must be contract or http");
        }
        if (!"http".equalsIgnoreCase(normalizedMode)) return new ContractOnlyArtifactStorage();
        return new S3CompatibleArtifactStorage(
                new ObjectStorageConfig(endpoint, bucket, region, prefix, accessKeyIdRef, secretAccessKeyRef),
                credentials, HttpClient.newHttpClient(), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "skill-center.package-upload-backend", havingValue = "distributed")
    S3ObjectClient resumableUploadObjectClient(
            @Value("${skill-center.artifact-storage.endpoint:}") String endpoint,
            @Value("${skill-center.artifact-storage.bucket:}") String bucket,
            @Value("${skill-center.artifact-storage.region:us-east-1}") String region,
            @Value("${skill-center.artifact-storage.prefix:skill-packages}") String prefix,
            @Value("${skill-center.artifact-storage.access-key-id-ref:}") String accessKeyIdRef,
            @Value("${skill-center.artifact-storage.secret-access-key-ref:}") String secretAccessKeyRef,
            ObjectStorageCredentialResolver credentials) {
        return new S3CompatibleArtifactStorage(
                new ObjectStorageConfig(endpoint, bucket, region, prefix, accessKeyIdRef, secretAccessKeyRef),
                credentials, HttpClient.newHttpClient(), Clock.systemUTC());
    }

    @Bean
    ObjectStorageCredentialResolver objectStorageCredentialResolver() {
        return new EnvironmentObjectStorageCredentialResolver();
    }
}
