package com.huawei.skillcenter.quality;

import java.util.Locale;
import java.util.Set;

public final class OptimizationExperimentStatus {
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final Set<String> ALL = Set.of(QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED);
    public static final Set<String> TERMINAL = Set.of(COMPLETED, FAILED, CANCELLED);

    private OptimizationExperimentStatus() {
    }

    public static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ALL.contains(normalized)) {
            throw new IllegalArgumentException("status must be a valid optimization experiment status");
        }
        return normalized;
    }

    public static boolean isTerminal(String value) {
        return TERMINAL.contains(normalize(value));
    }
}
