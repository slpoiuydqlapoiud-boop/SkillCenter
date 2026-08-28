package com.huawei.skillcenter.operations;

import java.util.List;

/** Fixed, auditable evidence required before a production handoff can be ready. */
public final class ProductionEvidenceCatalog {
    private static final List<String> REQUIRED_IDS = List.of(
            "BACKUP_PITR",
            "DATABASE_CAPACITY_SLO",
            "OBJECT_STORAGE",
            "PROVIDER_SECURITY",
            "REDIS_HA",
            "RELEASE_APPROVAL",
            "ROLLBACK_DRILL",
            "SLO_UAT",
            "SSO_ORGANIZATION");

    private ProductionEvidenceCatalog() {
    }

    public static List<String> requiredIds() {
        return REQUIRED_IDS;
    }

    public static boolean contains(String evidenceId) {
        return evidenceId != null && REQUIRED_IDS.contains(evidenceId.trim().toUpperCase());
    }

    public static String requireId(String evidenceId) {
        String normalized = evidenceId == null ? "" : evidenceId.trim().toUpperCase();
        if (!contains(normalized)) {
            throw new IllegalArgumentException("unknown production evidence id");
        }
        return normalized;
    }
}
