package com.huawei.skillcenter.operations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Safe, read-only readiness projection for a production handoff decision. */
public record PlatformReadiness(
        String overall,
        String scope,
        Instant checkedAt,
        List<Component> components,
        List<String> blockingReasonCodes) {

    public PlatformReadiness {
        overall = required(overall, "overall");
        scope = required(scope, "scope");
        checkedAt = checkedAt == null ? Instant.EPOCH : checkedAt;
        components = normalizeComponents(components);
        blockingReasonCodes = stableCodes(blockingReasonCodes);
    }

    public Component component(String componentId) {
        return components.stream()
                .filter(component -> component.componentId().equals(componentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("component not found: " + componentId));
    }

    public record Component(String componentId, String status, String reasonCode, String summary) {
        public Component {
            componentId = required(componentId, "componentId");
            status = required(status, "status");
            reasonCode = normalize(reasonCode);
            summary = bounded(summary);
        }
    }

    private static List<Component> normalizeComponents(List<Component> values) {
        List<Component> normalized = new ArrayList<>();
        if (values != null) {
            for (Component value : values) {
                if (value != null) normalized.add(value);
            }
        }
        normalized.sort(Comparator.comparing(Component::componentId));
        return List.copyOf(normalized);
    }

    private static List<String> stableCodes(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) {
            values.stream().map(PlatformReadiness::normalize)
                    .filter(value -> !value.isBlank()).forEach(unique::add);
        }
        return unique.stream().sorted().toList();
    }

    private static String required(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String bounded(String value) {
        String normalized = normalize(value);
        return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("[\\p{Cntrl}]", "");
    }
}
