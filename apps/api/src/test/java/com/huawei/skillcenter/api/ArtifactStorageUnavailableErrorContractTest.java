package com.huawei.skillcenter.api;

import com.huawei.skillcenter.distribution.ArtifactStorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStorageUnavailableErrorContractTest {
    @Test
    void mapsUnavailableArtifactStorageToStableServiceUnavailableEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new com.huawei.skillcenter.operations.OperationsMetricsService(Clock.systemUTC(), "", false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-1");

        var response = handler.artifactStorageUnavailable(
                new ArtifactStorageUnavailableException("object-storage",
                        "ARTIFACT_STORAGE_OBJECT_ADAPTER_NOT_CONFIGURED"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("ARTIFACT_STORAGE_OBJECT_ADAPTER_NOT_CONFIGURED");
        assertThat(response.getBody().error().message()).isEqualTo("Artifact storage is temporarily unavailable");
        assertThat(response.getBody().requestId()).isEqualTo("request-1");
    }
}
