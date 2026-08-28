package com.huawei.skillcenter.quality;

import java.util.Set;

/** Bounded retry policy for transient Provider failures; it never retries arbitrary business errors. */
public record ProviderRetryPolicy(
        int maxAttempts,
        long initialBackoffMs,
        long maxBackoffMs,
        Set<String> retryableErrorCodes
) {
    public ProviderRetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 5");
        }
        if (initialBackoffMs < 0 || initialBackoffMs > 60_000) {
            throw new IllegalArgumentException("initialBackoffMs must be between 0 and 60000");
        }
        if (maxBackoffMs < initialBackoffMs || maxBackoffMs > 120_000) {
            throw new IllegalArgumentException("maxBackoffMs must be at least initialBackoffMs");
        }
        retryableErrorCodes = Set.copyOf(retryableErrorCodes == null ? Set.of() : retryableErrorCodes);
    }

    public static ProviderRetryPolicy defaultPolicy() {
        return new ProviderRetryPolicy(2, 0, 0,
                Set.of("UPSTREAM_TIMEOUT", "RATE_LIMITED", "TEMPORARY_UNAVAILABLE"));
    }

    public boolean shouldRetry(RunnerExecutionResult result, int attempt) {
        if (result == null || attempt < 1 || attempt >= maxAttempts) {
            return false;
        }
        return result.errorCode() != null && retryableErrorCodes.contains(result.errorCode());
    }

    public long backoffMs(int attempt) {
        if (attempt <= 1 || initialBackoffMs == 0) {
            return initialBackoffMs;
        }
        long multiplier = 1L << Math.min(attempt - 1, 30);
        long candidate;
        try {
            candidate = Math.multiplyExact(initialBackoffMs, multiplier);
        } catch (ArithmeticException exception) {
            candidate = Long.MAX_VALUE;
        }
        return Math.min(maxBackoffMs, candidate);
    }
}
