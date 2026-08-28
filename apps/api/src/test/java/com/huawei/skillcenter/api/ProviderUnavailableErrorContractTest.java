package com.huawei.skillcenter.api;

import com.huawei.skillcenter.quality.ProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderUnavailableErrorContractTest {
    @Test
    void mapsUnavailableExternalProviderToStableServiceUnavailableEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new com.huawei.skillcenter.operations.OperationsMetricsService(Clock.systemUTC(), "", false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-1");

        var response = handler.providerUnavailable(
                new ProviderUnavailableException("openclaw-runner", "EXTERNAL_ADAPTER_NOT_CONFIGURED"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("EXTERNAL_ADAPTER_NOT_CONFIGURED");
        assertThat(response.getBody().error().message()).isEqualTo("External provider is temporarily unavailable");
        assertThat(response.getBody().requestId()).isEqualTo("request-1");
    }
}
