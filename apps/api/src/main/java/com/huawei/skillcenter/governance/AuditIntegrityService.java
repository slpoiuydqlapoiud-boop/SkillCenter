package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public class AuditIntegrityService {
    public static final String GENESIS_HASH = "GENESIS";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public AuditIntegrityEntry append(List<AuditIntegrityEntry> existing, AuditEvent event) {
        if (event == null || event.auditId() == null || event.auditId().isBlank()) {
            throw new IllegalArgumentException("audit event is required");
        }
        List<AuditIntegrityEntry> entries = existing == null ? List.of() : existing;
        long sequence = entries.size() + 1L;
        String previousHash = entries.isEmpty() ? GENESIS_HASH : entries.get(entries.size() - 1).hash();
        String hash = hash(previousHash, event);
        return new AuditIntegrityEntry(sequence, event.auditId(), previousHash, hash, "SHA-256", Instant.now());
    }

    public AuditIntegrityStatus verify(List<AuditEvent> audits, List<AuditIntegrityEntry> entries) {
        List<AuditEvent> events = audits == null ? List.of() : audits;
        List<AuditIntegrityEntry> chain = entries == null ? List.of() : entries;
        if (events.size() != chain.size()) {
            return AuditIntegrityStatus.invalid(chain.size(), "AUDIT_CHAIN_INVALID");
        }
        String previousHash = GENESIS_HASH;
        for (int index = 0; index < chain.size(); index++) {
            AuditIntegrityEntry entry = chain.get(index);
            AuditEvent event = events.get(index);
            if (entry.sequence() != index + 1L || !entry.auditId().equals(event.auditId())
                    || !entry.previousHash().equals(previousHash)
                    || !entry.hash().equals(hash(previousHash, event))) {
                return AuditIntegrityStatus.invalid(index + 1L, "AUDIT_CHAIN_INVALID");
            }
            previousHash = entry.hash();
        }
        return AuditIntegrityStatus.valid(chain.size());
    }

    private String hash(String previousHash, AuditEvent event) {
        try {
            String canonical = previousHash + "|" + objectMapper.writeValueAsString(new CanonicalAudit(
                    event.auditId(), event.action(), event.resourceType(), event.resourceId(), event.actorId(),
                    event.actorRole(), event.requestId(), event.occurredAt(), sortedMetadata(event.metadata())));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash audit event", exception);
        }
    }

    private Map<String, String> sortedMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        return metadata.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, java.util.LinkedHashMap::new));
    }

    private record CanonicalAudit(String auditId, String action, String resourceType, String resourceId,
                                  String actorId, String actorRole, String requestId, Instant occurredAt,
                                  Map<String, String> metadata) {}
}
