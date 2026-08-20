package com.huawei.skillcenter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OriginGuardFilter extends OncePerRequestFilter {
    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final SecurityBoundaryProperties properties;
    private final SecurityErrorWriter errorWriter;
    private final OperationsMetricsService metrics;

    public OriginGuardFilter(SecurityBoundaryProperties properties, SecurityErrorWriter errorWriter) {
        this(properties, errorWriter, null);
    }

    public OriginGuardFilter(SecurityBoundaryProperties properties, SecurityErrorWriter errorWriter,
                             OperationsMetricsService metrics) {
        this.properties = properties;
        this.errorWriter = errorWriter;
        this.metrics = metrics;
    }

    @Autowired
    public OriginGuardFilter(SecurityBoundaryProperties properties,
                             com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                             OperationsMetricsService metrics) {
        this(properties, new SecurityErrorWriter(objectMapper), metrics);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (isApiMutation(request) && origin != null && !isAllowedOrigin(origin)) {
            if (metrics != null) {
                metrics.recordSecurityEvent("CSRF_ORIGIN_REJECTED");
            }
            errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "CSRF_ORIGIN_REJECTED", "Request origin is not allowed");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isApiMutation(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/")
                && MUTATION_METHODS.contains(request.getMethod().toUpperCase());
    }

    private boolean isAllowedOrigin(String origin) {
        return properties.getAllowedOrigins().stream().anyMatch(origin::equals);
    }
}
