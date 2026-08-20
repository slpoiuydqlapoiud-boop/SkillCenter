package com.huawei.skillcenter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {
    @Test
    void apiResponseContainsSecurityHeaders() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/skills");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(response.getHeader("Permissions-Policy"))
                .isEqualTo("camera=(), microphone=(), geolocation=()");
    }

    @Test
    void securityErrorWriterUsesStableErrorEnvelope() throws Exception {
        SecurityErrorWriter writer = new SecurityErrorWriter(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events/invocations");
        request.setAttribute("com.huawei.skillcenter.api.RequestIdFilter.requestId", "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(request, response, 429, "RATE_LIMITED", "Too many requests");

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"")
                .contains("\"requestId\":\"request-123\"");
    }
}
