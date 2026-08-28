package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionEvidenceStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void upsertPersistsSortedEvidenceAndReloadsIt() {
        Path path = tempDir.resolve("production-evidence.json");
        ProductionEvidenceStore store = new ProductionEvidenceStore(path, new ObjectMapper().findAndRegisterModules());
        ProductionEvidence objectStorage = evidence("OBJECT_STORAGE", 1);
        ProductionEvidence database = evidence("DATABASE_CAPACITY_SLO", 1);

        store.upsert(objectStorage, 0);
        store.upsert(database, 0);

        assertThat(store.findAll()).extracting(ProductionEvidence::evidenceId)
                .containsExactly("DATABASE_CAPACITY_SLO", "OBJECT_STORAGE");
        ProductionEvidenceStore reloaded = new ProductionEvidenceStore(path, new ObjectMapper().findAndRegisterModules());
        assertThat(reloaded.find("OBJECT_STORAGE")).contains(objectStorage);
    }

    @Test
    void rejectsRevisionConflictAndSensitiveEvidenceMetadata() {
        ProductionEvidenceStore store = new ProductionEvidenceStore(
                tempDir.resolve("production-evidence.json"), new ObjectMapper().findAndRegisterModules());
        ProductionEvidence initial = evidence("SSO_ORGANIZATION", 1);
        store.upsert(initial, 0);

        assertThatThrownBy(() -> store.upsert(evidence("SSO_ORGANIZATION", 2), 0))
                .isInstanceOf(ProductionEvidenceConflictException.class);
        assertThatThrownBy(() -> store.upsert(new ProductionEvidence(
                "OBJECT_STORAGE", "ACCEPTED", "admin", NOW, NOW.plusSeconds(3600),
                "https://internal.example/report", "password=secret", 1, "admin", NOW), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.upsert(new ProductionEvidence(
                "OBJECT_STORAGE", "ACCEPTED", "admin", NOW, NOW.plusSeconds(3600),
                "change-2", "validated password=secret", 1, "admin", NOW), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ProductionEvidence evidence(String evidenceId, int revision) {
        return new ProductionEvidence(evidenceId, "ACCEPTED", "admin", NOW,
                NOW.plusSeconds(3600), "change-2026-001", "validated", revision, "admin", NOW);
    }
}
