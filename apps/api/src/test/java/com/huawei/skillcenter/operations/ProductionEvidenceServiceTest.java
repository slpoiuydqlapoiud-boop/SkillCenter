package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProductionEvidenceServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void missingAndExpiredEvidenceRemainNotReadyWithStableReasons() {
        ProductionEvidenceStore store = new ProductionEvidenceStore(
                tempDir.resolve("production-evidence.json"), new ObjectMapper().findAndRegisterModules());
        GovernanceStore governance = mock(GovernanceStore.class);
        ProductionEvidenceService service = new ProductionEvidenceService(
                store, governance, Clock.fixed(NOW, ZoneOffset.UTC));
        store.upsert(new ProductionEvidence("DATABASE_CAPACITY_SLO", "ACCEPTED", "admin", NOW.minusSeconds(10),
                NOW.minusSeconds(1), "change-1", "validated", 1, "admin", NOW.minusSeconds(10)), 0);

        ProductionEvidenceReadiness readiness = service.evaluate();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.blockingReasonCodes()).contains(
                "PRODUCTION_EVIDENCE_DATABASE_CAPACITY_SLO_EXPIRED",
                "PRODUCTION_EVIDENCE_SSO_ORGANIZATION_MISSING");
        assertThat(readiness.acceptedCount()).isEqualTo(0);
        assertThat(service.list(new Actor("admin", "admin"))).hasSize(9);
    }

    @Test
    void acceptedEvidenceRequiresSafeReferenceAndWritesAuditableUpdate() {
        ProductionEvidenceStore store = new ProductionEvidenceStore(
                tempDir.resolve("production-evidence.json"), new ObjectMapper().findAndRegisterModules());
        GovernanceStore governance = mock(GovernanceStore.class);
        ProductionEvidenceService service = new ProductionEvidenceService(
                store, governance, Clock.fixed(NOW, ZoneOffset.UTC));

        ProductionEvidence updated = service.update("DATABASE_CAPACITY_SLO",
                new ProductionEvidenceUpdateRequest("ACCEPTED", "owner", NOW.plusSeconds(3600),
                        "change-2026-001", "validated", 0),
                new Actor("admin", "admin"), "req-evidence-1");

        assertThat(updated.status()).isEqualTo("ACCEPTED");
        assertThat(updated.revision()).isEqualTo(1);
        verify(governance).addAudit(any());
    }

    @Test
    void allCurrentAcceptedEvidenceUnlocksProductionReadiness() {
        ProductionEvidenceStore store = new ProductionEvidenceStore(
                tempDir.resolve("production-evidence-ready.json"), new ObjectMapper().findAndRegisterModules());
        ProductionEvidenceService service = new ProductionEvidenceService(
                store, mock(GovernanceStore.class), Clock.fixed(NOW, ZoneOffset.UTC));
        for (String evidenceId : ProductionEvidenceCatalog.requiredIds()) {
            store.upsert(new ProductionEvidence(evidenceId, "ACCEPTED", "admin", NOW,
                    NOW.plusSeconds(3600), "change-" + evidenceId, "validated", 1, "admin", NOW), 0);
        }

        ProductionEvidenceReadiness readiness = service.evaluate();

        assertThat(readiness.status()).isEqualTo("READY");
        assertThat(readiness.acceptedCount()).isEqualTo(9);
        assertThat(readiness.blockingReasonCodes()).isEmpty();
    }

    @Test
    void nonAdminCannotChangeEvidence() {
        ProductionEvidenceService service = new ProductionEvidenceService(
                mock(ProductionEvidenceStore.class), mock(GovernanceStore.class), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.update("DATABASE_CAPACITY_SLO",
                new ProductionEvidenceUpdateRequest("ACCEPTED", "owner", NOW.plusSeconds(3600),
                        "change-1", "validated", 0), new Actor("developer", "developer"), "req-2"))
                .isInstanceOf(RuntimeException.class);
    }
}
