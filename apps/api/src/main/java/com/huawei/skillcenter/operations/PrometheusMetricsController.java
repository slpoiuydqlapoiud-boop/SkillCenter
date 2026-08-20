package com.huawei.skillcenter.operations;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

@RestController
public class PrometheusMetricsController {
    private static final MediaType PROMETHEUS_MEDIA_TYPE = MediaType.parseMediaType("text/plain;version=0.0.4");
    private final OperationsMetricsService metricsService;
    private final String configuredToken;

    @Autowired
    public PrometheusMetricsController(OperationsMetricsService metricsService,
                                       @Value("${skill-center.operations.metrics-token:}") String configuredToken) {
        this.metricsService = metricsService;
        this.configuredToken = configuredToken == null ? "" : configuredToken;
    }

    @GetMapping(value = "/internal/metrics", produces = "text/plain;version=0.0.4")
    ResponseEntity<String> metrics(
            @RequestHeader(value = "X-Metrics-Token", required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!StringUtils.hasText(configuredToken)) {
            throw new MetricsTokenException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "NOT_FOUND", "Metrics endpoint is disabled");
        }
        String presentedToken = StringUtils.hasText(token) ? token : bearerToken(authorization);
        if (!StringUtils.hasText(presentedToken) || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8), presentedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new MetricsTokenException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "METRICS_UNAUTHORIZED", "Metrics token is invalid");
        }
        return ResponseEntity.ok().contentType(PROMETHEUS_MEDIA_TYPE)
                .body(render(metricsService.snapshot(OperationsWindow.SIXTY_MINUTES)));
    }

    ResponseEntity<String> metrics(String token) {
        return metrics(token, null);
    }

    private String bearerToken(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorization.substring(7).trim();
    }

    private String render(OperationsMetricsSnapshot snapshot) {
        String window = snapshot.window().label();
        OperationsMetricsSnapshot.RequestCounts requests = snapshot.requests();
        OperationsMetricsSnapshot.LatencyMetrics latency = snapshot.latency();
        StringBuilder output = new StringBuilder();
        line(output, "# HELP skillcenter_requests_total Total API requests in the selected window.");
        line(output, "# TYPE skillcenter_requests_total gauge");
        line(output, metric("skillcenter_requests_total", window, requests.total()));
        line(output, "# HELP skillcenter_requests_successes_total Successful API requests.");
        line(output, "# TYPE skillcenter_requests_successes_total gauge");
        line(output, metric("skillcenter_requests_successes_total", window, requests.successes()));
        line(output, "# HELP skillcenter_requests_client_errors_total Client error responses.");
        line(output, "# TYPE skillcenter_requests_client_errors_total gauge");
        line(output, metric("skillcenter_requests_client_errors_total", window, requests.clientErrors()));
        line(output, "# HELP skillcenter_requests_server_errors_total Server error responses.");
        line(output, "# TYPE skillcenter_requests_server_errors_total gauge");
        line(output, metric("skillcenter_requests_server_errors_total", window, requests.serverErrors()));
        line(output, "# HELP skillcenter_request_error_rate_ratio Ratio of 5xx responses to all responses.");
        line(output, "# TYPE skillcenter_request_error_rate_ratio gauge");
        double errorRate = requests.total() == 0 ? 0D : (double) requests.serverErrors() / requests.total();
        line(output, metric("skillcenter_request_error_rate_ratio", window,
                String.format(Locale.ROOT, "%.6f", errorRate)));
        line(output, "# HELP skillcenter_request_latency_p95_ms Approximate P95 request latency in milliseconds.");
        line(output, "# TYPE skillcenter_request_latency_p95_ms gauge");
        line(output, metric("skillcenter_request_latency_p95_ms", window, latency.p95Ms()));
        line(output, "# HELP skillcenter_request_latency_max_ms Maximum request latency in milliseconds.");
        line(output, "# TYPE skillcenter_request_latency_max_ms gauge");
        line(output, metric("skillcenter_request_latency_max_ms", window, latency.maxMs()));
        line(output, "# HELP skillcenter_security_events_total Security boundary events.");
        line(output, "# TYPE skillcenter_security_events_total gauge");
        snapshot.securityEvents().entrySet().stream().sorted(MapEntryComparator.INSTANCE).forEach(entry ->
                line(output, "skillcenter_security_events_total{window=\"" + window + "\",event=\""
                        + entry.getKey() + "\"} " + entry.getValue()));
        line(output, "# HELP skillcenter_metrics_persistence_status Metrics persistence status (1=enabled, 0=otherwise).");
        line(output, "# TYPE skillcenter_metrics_persistence_status gauge");
        line(output, metric("skillcenter_metrics_persistence_status", window,
                "ENABLED".equals(snapshot.health().metricsPersistence()) ? 1 : 0));
        return output.toString();
    }

    private static String metric(String name, String window, Object value) {
        return name + "{window=\"" + window + "\"} " + value;
    }

    private static void line(StringBuilder output, String line) {
        output.append(line).append('\n');
    }

    private enum MapEntryComparator implements java.util.Comparator<java.util.Map.Entry<String, Long>> {
        INSTANCE;

        @Override
        public int compare(java.util.Map.Entry<String, Long> left, java.util.Map.Entry<String, Long> right) {
            return left.getKey().compareTo(right.getKey());
        }
    }
}
