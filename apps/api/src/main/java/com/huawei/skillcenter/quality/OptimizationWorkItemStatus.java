package com.huawei.skillcenter.quality;

import java.util.Locale;
import java.util.Set;

public final class OptimizationWorkItemStatus {
    public static final String OPEN = "OPEN";
    public static final String PLANNED = "PLANNED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String READY_FOR_EVALUATION = "READY_FOR_EVALUATION";
    public static final String COMPLETED = "COMPLETED";
    public static final String ABANDONED = "ABANDONED";
    public static final Set<String> ALL = Set.of(OPEN, PLANNED, IN_PROGRESS, READY_FOR_EVALUATION, COMPLETED, ABANDONED);
    public static final Set<String> TERMINAL = Set.of(COMPLETED, ABANDONED);

    private OptimizationWorkItemStatus() {
    }

    public static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ALL.contains(normalized)) {
            throw new IllegalArgumentException("status must be a valid optimization work item status");
        }
        return normalized;
    }

    public static boolean isTerminal(String value) {
        return TERMINAL.contains(normalize(value));
    }
}
