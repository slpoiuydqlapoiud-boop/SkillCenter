package com.huawei.skillcenter.search;

/** Publicly bounded operational projection; source hashes and source documents stay server-side. */
public record SkillSearchIndexAdminView(
        String backend,
        String status,
        int revision,
        int documentCount,
        String indexedAt,
        String reasonCode
) {
    static SkillSearchIndexAdminView from(SkillSearchIndexStatus status) {
        return new SkillSearchIndexAdminView("json", status.state(), status.revision(), status.documentCount(),
                status.indexedAt() == null ? "" : status.indexedAt().toString(), status.reasonCode());
    }
}
