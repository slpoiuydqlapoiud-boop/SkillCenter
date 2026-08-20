package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditIntegrityServiceTest {
    private final AuditIntegrityService service = new AuditIntegrityService();

    @Test
    void appendsAndVerifiesOrderedHashChain() {
        List<AuditEvent> audits = List.of(
                audit("a1", "EXPORT_CREATED"),
                audit("a2", "EXPORT_DOWNLOAD"));
        List<AuditIntegrityEntry> chain = new ArrayList<>();
        chain.add(service.append(chain, audits.get(0)));
        chain.add(service.append(chain, audits.get(1)));

        AuditIntegrityStatus status = service.verify(audits, chain);

        assertThat(status.valid()).isTrue();
        assertThat(chain).extracting(AuditIntegrityEntry::sequence).containsExactly(1L, 2L);
        assertThat(chain.get(1).previousHash()).isEqualTo(chain.get(0).hash());
    }

    @Test
    void detectsTamperedEventAndBrokenPreviousHash() {
        List<AuditEvent> audits = List.of(audit("a1", "EXPORT_CREATED"), audit("a2", "EXPORT_DOWNLOAD"));
        List<AuditIntegrityEntry> chain = List.of(service.append(List.of(), audits.get(0)),
                service.append(List.of(service.append(List.of(), audits.get(0))), audits.get(1)));

        List<AuditEvent> tampered = List.of(audits.get(0), audit("a2", "RETENTION_EXECUTED"));
        AuditIntegrityStatus status = service.verify(tampered, chain);

        assertThat(status.valid()).isFalse();
        assertThat(status.errorCode()).isEqualTo("AUDIT_CHAIN_INVALID");
    }

    private AuditEvent audit(String id, String action) {
        return new AuditEvent(id, action, "EXPORT", id, "admin", "admin", "req-1",
                Instant.parse("2026-08-18T02:00:00Z"), Map.of("dataset", "AUDIT_SUMMARY"));
    }
}
