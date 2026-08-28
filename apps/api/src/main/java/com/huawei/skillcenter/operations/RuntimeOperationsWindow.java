package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum RuntimeOperationsWindow {
    FIVE_MINUTES("5m", 5 * 60),
    FIFTEEN_MINUTES("15m", 15 * 60),
    SIXTY_MINUTES("60m", 60 * 60),
    TWENTY_FOUR_HOURS("24h", 24 * 60 * 60),
    SEVEN_DAYS("7d", 7 * 24 * 60 * 60);

    private final String label;
    private final long seconds;

    RuntimeOperationsWindow(String label, long seconds) {
        this.label = label;
        this.seconds = seconds;
    }

    public static RuntimeOperationsWindow parse(String value) {
        return Arrays.stream(values()).filter(item -> item.label.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("window must be one of 5m, 15m, 60m, 24h or 7d"));
    }

    @JsonValue
    public String label() {
        return label;
    }

    public long seconds() {
        return seconds;
    }
}
