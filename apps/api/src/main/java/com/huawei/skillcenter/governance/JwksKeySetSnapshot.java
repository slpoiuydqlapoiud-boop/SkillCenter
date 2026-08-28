package com.huawei.skillcenter.governance;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;

/** Immutable in-memory JWKS key set with its fetch timestamp. */
public record JwksKeySetSnapshot(Map<String, PublicKey> keys, Instant loadedAt) {
    public JwksKeySetSnapshot {
        if (keys == null || keys.isEmpty() || loadedAt == null) {
            throw new IllegalArgumentException("JWKS snapshot is invalid");
        }
        keys = Map.copyOf(keys);
    }
}
