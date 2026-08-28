package com.huawei.skillcenter.operations;

import java.util.List;
import java.util.Optional;

/** Persistence port for safe production handoff evidence metadata. */
public interface ProductionEvidenceRepository {
    List<ProductionEvidence> findAll();

    Optional<ProductionEvidence> find(String evidenceId);

    ProductionEvidence upsert(ProductionEvidence evidence, int expectedRevision);
}
