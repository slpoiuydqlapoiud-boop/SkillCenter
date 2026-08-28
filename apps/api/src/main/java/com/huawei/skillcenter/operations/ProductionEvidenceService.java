package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductionEvidenceService {
    private static final String REQUIRED_REASON = "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED";
    private final ProductionEvidenceRepository store;
    private final GovernanceStore governanceStore;
    private final Clock clock;

    @Autowired
    public ProductionEvidenceService(ProductionEvidenceRepository store, GovernanceStore governanceStore) {
        this(store, governanceStore, Clock.systemUTC());
    }

    public ProductionEvidenceService(ProductionEvidenceRepository store, GovernanceStore governanceStore, Clock clock) {
        this.store = require(store, "store");
        this.governanceStore = require(governanceStore, "governanceStore");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<ProductionEvidence> list(Actor actor) {
        requireAdmin(actor);
        Map<String, ProductionEvidence> persisted = new HashMap<>();
        for (ProductionEvidence value : store.findAll()) persisted.put(value.evidenceId(), value);
        List<ProductionEvidence> result = new ArrayList<>();
        for (String evidenceId : ProductionEvidenceCatalog.requiredIds()) {
            result.add(persisted.getOrDefault(evidenceId, missing(evidenceId)));
        }
        return List.copyOf(result);
    }

    public ProductionEvidenceReadiness evaluate() {
        Instant now = clock.instant();
        try {
            Map<String, ProductionEvidence> persisted = new HashMap<>();
            for (ProductionEvidence value : store.findAll()) persisted.put(value.evidenceId(), value);
            List<String> blocking = new ArrayList<>();
            int accepted = 0;
            for (String evidenceId : ProductionEvidenceCatalog.requiredIds()) {
                ProductionEvidence value = persisted.get(evidenceId);
                if (value == null || "MISSING".equals(value.status())) {
                    blocking.add(reason(evidenceId, "MISSING"));
                } else if (!"ACCEPTED".equals(value.status())) {
                    blocking.add(reason(evidenceId, value.status()));
                } else if (value.expiresAt() == null || !value.expiresAt().isAfter(now)) {
                    blocking.add(reason(evidenceId, "EXPIRED"));
                } else {
                    accepted++;
                }
            }
            String status = blocking.isEmpty() ? "READY" : "NOT_READY";
            return new ProductionEvidenceReadiness(status,
                    blocking.isEmpty() ? "PRODUCTION_EXTERNAL_EVIDENCE_READY" : REQUIRED_REASON,
                    ProductionEvidenceCatalog.requiredIds().size(), accepted, blocking);
        } catch (RuntimeException exception) {
            return new ProductionEvidenceReadiness("NOT_READY", "PRODUCTION_EVIDENCE_STORE_UNAVAILABLE",
                    ProductionEvidenceCatalog.requiredIds().size(), 0,
                    List.of("PRODUCTION_EVIDENCE_STORE_UNAVAILABLE"));
        }
    }

    public ProductionEvidence update(String evidenceId, ProductionEvidenceUpdateRequest request,
                                     Actor actor, String requestId) {
        requireAdmin(actor);
        String normalizedId = ProductionEvidenceCatalog.requireId(evidenceId);
        if (request == null) throw new IllegalArgumentException("production evidence update is required");
        String status = request.status() == null ? "" : request.status().trim().toUpperCase();
        Instant now = clock.instant();
        if ("ACCEPTED".equals(status)
                && (request.expiresAt() == null || !request.expiresAt().isAfter(now))) {
            throw new IllegalArgumentException("accepted evidence must expire in the future");
        }
        ProductionEvidence existing = store.find(normalizedId).orElse(null);
        int expectedRevision = request.expectedRevision() == null ? 0 : request.expectedRevision();
        int nextRevision = (existing == null ? 0 : existing.revision()) + 1;
        String owner = "MISSING".equals(status) ? "" : blankTo(request.ownerUserId(), actor.userId());
        ProductionEvidence updated = new ProductionEvidence(normalizedId, status, owner,
                "ACCEPTED".equals(status) ? now : null,
                request.expiresAt(), request.evidenceRef(), request.summary(), nextRevision,
                actor.userId(), now);
        ProductionEvidence result = store.upsert(updated, expectedRevision);
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(),
                "PRODUCTION_EVIDENCE_UPDATED", "PRODUCTION_EVIDENCE", result.evidenceId(),
                actor.userId(), actor.role(), requestId, now,
                Map.of("status", result.status(), "revision", Integer.toString(result.revision()))));
        return result;
    }

    private ProductionEvidence missing(String evidenceId) {
        return new ProductionEvidence(evidenceId, "MISSING", "", null, null, "", "", 0, "system", Instant.EPOCH);
    }

    private String reason(String evidenceId, String suffix) {
        return "PRODUCTION_EVIDENCE_" + evidenceId + "_" + suffix;
    }

    private void requireAdmin(Actor actor) {
        RoleGuard.require(actor, Set.of("admin"));
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
