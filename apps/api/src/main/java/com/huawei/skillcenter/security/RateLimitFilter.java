package com.huawei.skillcenter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService service;
    private final SecurityErrorWriter errorWriter;
    private final OperationsMetricsService metrics;

    @Autowired
    public RateLimitFilter(RateLimitService service, ObjectMapper objectMapper,
                           OperationsMetricsService metrics) {
        this(service, new SecurityErrorWriter(objectMapper), metrics);
    }

    public RateLimitFilter(RateLimitService service, SecurityErrorWriter errorWriter) {
        this(service, errorWriter, null);
    }

    public RateLimitFilter(RateLimitService service, SecurityErrorWriter errorWriter,
                           OperationsMetricsService metrics) {
        this.service = service;
        this.errorWriter = errorWriter;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String scope = scope(request.getMethod(), request.getRequestURI());
        if (scope == null) {
            filterChain.doFilter(request, response);
            return;
        }
        RateLimitDecision decision = service.check(scope, subject(request));
        writeHeaders(response, decision);
        if (!decision.allowed()) {
            if (metrics != null) {
                metrics.recordSecurityEvent("RATE_LIMITED");
            }
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            errorWriter.write(request, response, 429, "RATE_LIMITED", "Request rate limit exceeded");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeHeaders(HttpServletResponse response, RateLimitDecision decision) {
        if (decision.limit() < 0) return;
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetEpochSeconds()));
    }

    private String subject(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) return "user:" + userId.trim();
        String clientId = request.getHeader("X-Client-Id");
        if (clientId != null && !clientId.isBlank()) return "client:" + clientId.trim();
        return "ip:" + (request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr());
    }

    private String scope(String method, String uri) {
        String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if ("POST".equals(normalizedMethod) && uri.matches("/api/v1/skills/[^/]+/installations/?")) {
            return "INSTALLATION_CREATE";
        }
        if ("POST".equals(normalizedMethod)
                && uri.matches("/api/v1/distribution/authorizations/[^/]+/consume/?")) {
            return "DISTRIBUTION_CONSUME";
        }
        if ("GET".equals(normalizedMethod)
                && uri.matches("/api/v1/distribution/artifacts/[^/]+/[^/]+/?")) {
            return "DISTRIBUTION_DOWNLOAD";
        }
        if ("POST".equals(normalizedMethod)
                && uri.matches("/api/v1/events/invocations(?:/batch)?/?")) {
            return "INVOCATION_INGEST";
        }
        if ("POST".equals(normalizedMethod)
                && uri.matches("/api/v1/events/runtime-summaries(?:/batch)?/?")) {
            return "RUNTIME_SUMMARY_INGEST";
        }
        if ("POST".equals(normalizedMethod) && "/api/v1/admin/exports".equals(uri)) {
            return "EXPORT_CREATE";
        }
        return null;
    }
}
