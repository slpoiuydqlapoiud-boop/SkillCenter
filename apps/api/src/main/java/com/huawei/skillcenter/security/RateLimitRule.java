package com.huawei.skillcenter.security;

import java.time.Duration;
import java.util.Objects;

public record RateLimitRule(int limit, Duration window) {
    public RateLimitRule {
        if (limit < 1) {
            throw new IllegalArgumentException("rate limit must be positive");
        }
        window = Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("rate limit window must be positive");
        }
    }
}
