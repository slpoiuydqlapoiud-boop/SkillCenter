package com.huawei.skillcenter.security;

public record RateLimitDecision(boolean allowed, int limit, int remaining,
                                long retryAfterSeconds, long resetEpochSeconds) {
    public static RateLimitDecision unlimited() {
        return new RateLimitDecision(true, -1, -1, 0, 0);
    }
}
