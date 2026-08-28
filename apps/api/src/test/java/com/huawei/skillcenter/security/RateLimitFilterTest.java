package com.huawei.skillcenter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import com.huawei.skillcenter.operations.OperationsWindow;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {
    @Test
    void rejectsInstallationRequestsWithRetryHeaders() throws Exception {
        RateLimitService service = new RateLimitService(Clock.systemUTC(),
                Map.of("INSTALLATION_CREATE", new RateLimitRule(1, Duration.ofMinutes(1))));
        OperationsMetricsService metrics = new OperationsMetricsService(Clock.systemUTC(), "./data/packages", true);
        RateLimitFilter filter = new RateLimitFilter(service, new SecurityErrorWriter(new ObjectMapper()), metrics);
        FilterChain chain = (request, response) -> { };

        MockHttpServletRequest first = request("POST", "/api/v1/skills/demo/installations", "user-1");
        filter.doFilter(first, new MockHttpServletResponse(), chain);

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/v1/skills/demo/installations", "user-1"), rejected, chain);

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isNotBlank();
        assertThat(rejected.getHeader("X-RateLimit-Limit")).isEqualTo("1");
        assertThat(rejected.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(rejected.getContentAsString()).contains("RATE_LIMITED");
        assertThat(metrics.snapshot(OperationsWindow.FIVE_MINUTES).securityEvents())
                .containsEntry("RATE_LIMITED", 1L);
    }

    @Test
    void leavesMarketQueriesOutsideRateLimitedScopes() throws Exception {
        RateLimitService service = new RateLimitService(Clock.systemUTC(), Map.of());
        RateLimitFilter filter = new RateLimitFilter(service, new SecurityErrorWriter(new ObjectMapper()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/api/v1/skills", "user-1"), response,
                (request, servletResponse) -> ((MockHttpServletResponse) servletResponse).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rateLimitsRuntimeSummaryIngestionAndBatchEndpoints() throws Exception {
        RateLimitService service = new RateLimitService(Clock.systemUTC(),
                Map.of("RUNTIME_SUMMARY_INGEST", new RateLimitRule(1, Duration.ofMinutes(1))));
        RateLimitFilter filter = new RateLimitFilter(service, new SecurityErrorWriter(new ObjectMapper()));
        FilterChain chain = (request, response) -> ((MockHttpServletResponse) response).setStatus(202);

        filter.doFilter(request("POST", "/api/v1/events/runtime-summaries", "client-1"),
                new MockHttpServletResponse(), chain);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/v1/events/runtime-summaries/batch", "client-1"), rejected, chain);

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("X-RateLimit-Limit")).isEqualTo("1");
    }

    private static MockHttpServletRequest request(String method, String path, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-User-Id", userId);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
