package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.api.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PrometheusMetricsControllerTest {
    private final OperationsMetricsService metrics = new OperationsMetricsService(
            Clock.fixed(Instant.parse("2026-08-18T06:00:10Z"), ZoneOffset.UTC), "", false);

    @Test
    void disabledEndpointReturnsNotFound() throws Exception {
        mvc("").perform(get("/internal/metrics"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingOrWrongTokenReturnsUnauthorized() throws Exception {
        MockMvc client = mvc("secret");
        client.perform(get("/internal/metrics"))
                .andExpect(status().isUnauthorized());
        client.perform(get("/internal/metrics").header("X-Metrics-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenReturnsFixedPrometheusMetricsWithoutSensitiveFields() throws Exception {
        metrics.recordRequest(200, 120);
        metrics.recordRequest(500, 1_500);
        metrics.recordSecurityEvent("RATE_LIMITED");

        mvc("secret").perform(get("/internal/metrics").header("X-Metrics-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("text/plain;version=0.0.4")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("skillcenter_requests_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("skillcenter_request_latency_p95_ms")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("skillcenter_security_events_total")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("token"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("userId"))));
    }

    @Test
    void standardBearerTokenCanScrapeMetrics() throws Exception {
        metrics.recordRequest(200, 20);

        mvc("secret").perform(get("/internal/metrics")
                        .header("Authorization", "Bearer secret"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("skillcenter_requests_total")));
    }

    private MockMvc mvc(String token) {
        return MockMvcBuilders.standaloneSetup(new PrometheusMetricsController(metrics, token))
                .setControllerAdvice(new GlobalExceptionHandler(metrics))
                .build();
    }
}
