package com.huawei.skillcenter.search;

/** Stable, redacted failure boundary for an external search index. */
public final class SkillSearchIndexRemoteException extends RuntimeException {
    private final String reasonCode;

    public SkillSearchIndexRemoteException(String reasonCode) {
        super("External search index operation failed");
        this.reasonCode = SkillSearchDocument.boundedRequired(reasonCode, "reasonCode", 128);
    }

    public String reasonCode() {
        return reasonCode;
    }
}
