package com.huawei.skillcenter.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {
    private final Clock clock;
    private final Duration ttl;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public IdempotencyService(SecurityBoundaryProperties properties) {
        this(Clock.systemUTC(), Duration.ofSeconds(Math.max(1, properties.getIdempotencyTtlSeconds())));
    }

    public IdempotencyService(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(1) : ttl;
    }

    public void claim(String scope, String actor, String key, String fingerprint) {
        if (key == null) {
            return;
        }
        validateKey(key);
        String identity = identity(scope, actor, key);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        String safeFingerprint = fingerprint == null ? "" : fingerprint;
        entries.compute(identity, (ignored, existing) -> {
            if (existing == null || !existing.expiresAt().isAfter(now)) {
                return new Entry(safeFingerprint, expiresAt);
            }
            if (existing.fingerprint().equals(safeFingerprint)) {
                throw new IdempotencyException("IDEMPOTENCY_REPLAY", "Request has already been accepted");
            }
            throw new IdempotencyException("IDEMPOTENCY_CONFLICT", "Idempotency key was used with different request data");
        });
    }

    public void release(String scope, String actor, String key, String fingerprint) {
        if (key == null || key.isBlank()) {
            return;
        }
        String identity = identity(scope, actor, key);
        String safeFingerprint = fingerprint == null ? "" : fingerprint;
        entries.computeIfPresent(identity, (ignored, existing) ->
                existing.fingerprint().equals(safeFingerprint) ? null : existing);
    }

    public void purgeExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    public int size() {
        purgeExpired();
        return entries.size();
    }

    private void validateKey(String key) {
        if (key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw new IdempotencyException("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key is invalid");
        }
    }

    private String identity(String scope, String actor, String key) {
        return (scope == null ? "" : scope) + "\u0000" + (actor == null ? "" : actor) + "\u0000" + key;
    }

    private record Entry(String fingerprint, Instant expiresAt) {
    }
}
