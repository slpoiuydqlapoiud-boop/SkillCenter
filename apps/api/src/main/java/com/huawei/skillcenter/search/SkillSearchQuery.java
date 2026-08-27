package com.huawei.skillcenter.search;

import java.util.Locale;

public record SkillSearchQuery(String text, String category, String status, String risk, String sort) {
    public SkillSearchQuery {
        text = normalizeText(text, "text", 512);
        category = SkillSearchDocument.bounded(category, "category", 256);
        status = normalizeFilter(status, "status", 32);
        risk = normalizeFilter(risk, "risk", 32);
        sort = normalizeSort(sort);
    }

    public static SkillSearchQuery of(String text, String category, String status, String risk, String sort) {
        return new SkillSearchQuery(text, category, status, risk, sort);
    }

    private static String normalizeText(String value, String field, int maxLength) {
        String bounded = SkillSearchDocument.bounded(value, field, maxLength);
        return bounded.replaceAll("\\s+", " ");
    }

    private static String normalizeFilter(String value, String field, int maxLength) {
        return SkillSearchDocument.bounded(value, field, maxLength).toLowerCase(Locale.ROOT);
    }

    private static String normalizeSort(String value) {
        String normalized = SkillSearchDocument.bounded(value, "sort", 32).toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "updated" : normalized;
    }
}
