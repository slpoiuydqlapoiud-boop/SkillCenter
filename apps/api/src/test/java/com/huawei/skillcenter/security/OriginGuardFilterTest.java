package com.huawei.skillcenter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import com.huawei.skillcenter.operations.OperationsWindow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class OriginGuardFilterTest {
    @Test
    void rejectsCrossOriginApiMutationWithStableErrorEnvelope() throws Exception {
        SecurityBoundaryProperties properties = new SecurityBoundaryProperties();
        OperationsMetricsService metrics = new OperationsMetricsService(java.time.Clock.systemUTC(), "./data/packages", true);
        OriginGuardFilter filter = new OriginGuardFilter(properties, new SecurityErrorWriter(new ObjectMapper()), metrics);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events/invocations");
        request.addHeader("Origin", "https://evil.example");
        request.setAttribute("com.huawei.skillcenter.api.RequestIdFilter.requestId", "request-456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(201));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"CSRF_ORIGIN_REJECTED\"")
                .contains("\"requestId\":\"request-456\"");
        assertThat(metrics.snapshot(OperationsWindow.FIVE_MINUTES).securityEvents())
                .containsEntry("CSRF_ORIGIN_REJECTED", 1L);
    }

    @Test
    void allowsConfiguredOriginAndCliRequestWithoutOrigin() throws Exception {
        SecurityBoundaryProperties properties = new SecurityBoundaryProperties();
        OriginGuardFilter filter = new OriginGuardFilter(properties, new SecurityErrorWriter(new ObjectMapper()));

        MockHttpServletRequest browserRequest = new MockHttpServletRequest("POST", "/api/v1/events/invocations");
        browserRequest.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse browserResponse = new MockHttpServletResponse();
        filter.doFilter(browserRequest, browserResponse, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(201));

        MockHttpServletRequest cliRequest = new MockHttpServletRequest("POST", "/api/v1/events/invocations");
        MockHttpServletResponse cliResponse = new MockHttpServletResponse();
        filter.doFilter(cliRequest, cliResponse, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(201));

        assertThat(browserResponse.getStatus()).isEqualTo(201);
        assertThat(cliResponse.getStatus()).isEqualTo(201);
    }

    @Test
    void onlyProtectsApiMutations() throws Exception {
        SecurityBoundaryProperties properties = new SecurityBoundaryProperties();
        OriginGuardFilter filter = new OriginGuardFilter(properties, new SecurityErrorWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/health");
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(204));

        assertThat(response.getStatus()).isEqualTo(204);
    }
}
