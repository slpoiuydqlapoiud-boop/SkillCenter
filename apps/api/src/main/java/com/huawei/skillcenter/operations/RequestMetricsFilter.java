package com.huawei.skillcenter.operations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RequestMetricsFilter extends OncePerRequestFilter {
    private final OperationsMetricsService metrics;

    public RequestMetricsFilter(OperationsMetricsService metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            metrics.recordRequest(response.getStatus(), durationMs);
        }
    }
}
