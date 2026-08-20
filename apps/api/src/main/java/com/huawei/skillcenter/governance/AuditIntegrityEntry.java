package com.huawei.skillcenter.governance;

import java.time.Instant;

public record AuditIntegrityEntry(
        long sequence,
        String auditId,
        String previousHash,
        String hash,
        String algorithm,
        Instant createdAt
) {
    public AuditIntegrityEntry {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (auditId == null || auditId.isBlank()) {
            throw new IllegalArgumentException("auditId is required");
        }
        if (previousHash == null || previousHash.isBlank()) {
            throw new IllegalArgumentException("previousHash is required");
        }
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("hash is required");
        }
        if (!"SHA-256".equals(algorithm)) {
            throw new IllegalArgumentException("algorithm must be SHA-256");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
    }
}
