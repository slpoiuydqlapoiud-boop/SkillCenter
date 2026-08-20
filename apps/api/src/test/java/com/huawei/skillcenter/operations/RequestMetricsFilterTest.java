package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMetricsFilterTest {
    @Test
    void recordsApiStatusAndLatencyAfterDownstreamChain() throws Exception {
        OperationsMetricsService metrics = new OperationsMetricsService(
                Clock.fixed(Instant.parse("2026-08-18T06:00:10Z"), ZoneOffset.UTC), "./data/packages", true);
        RequestMetricsFilter filter = new RequestMetricsFilter(metrics);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/skills"), response,
                (request, servletResponse) -> ((MockHttpServletResponse) servletResponse).setStatus(503));

        OperationsMetricsSnapshot snapshot = metrics.snapshot(OperationsWindow.FIVE_MINUTES);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(snapshot.requests().total()).isEqualTo(1);
        assertThat(snapshot.requests().serverErrors()).isEqualTo(1);
        assertThat(snapshot.latency().maxMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void ignoresNonApiRequests() throws Exception {
        OperationsMetricsService metrics = new OperationsMetricsService(
                Clock.systemUTC(), "./data/packages", true);
        RequestMetricsFilter filter = new RequestMetricsFilter(metrics);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), response,
                (request, servletResponse) -> ((MockHttpServletResponse) servletResponse).setStatus(200));

        assertThat(metrics.snapshot(OperationsWindow.FIVE_MINUTES).requests().total()).isZero();
    }
}
