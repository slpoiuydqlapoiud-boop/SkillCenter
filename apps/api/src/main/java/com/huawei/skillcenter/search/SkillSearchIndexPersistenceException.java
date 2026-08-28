package com.huawei.skillcenter.search;

/** Stable internal boundary for shared search-index failures. */
public final class SkillSearchIndexPersistenceException extends RuntimeException {
    public SkillSearchIndexPersistenceException(Throwable cause) {
        super("Search index persistence is unavailable", cause);
    }
}
