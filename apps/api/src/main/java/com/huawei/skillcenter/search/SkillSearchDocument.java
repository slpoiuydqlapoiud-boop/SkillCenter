package com.huawei.skillcenter.search;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record SkillSearchDocument(
        String skillId,
        String name,
        String description,
        List<String> tags,
        String team,
        String category,
        String status,
        String risk,
        Instant lastUpdated,
        Instant publishedAt,
        String latestVersion,
        String visibility,
        String ownerTeamId) {

    private static final Set<String> STATUSES = Set.of("published", "deprecated", "withdrawn");
    private static final Set<String> RISKS = Set.of("low", "medium", "high");
    private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "TEAM", "RESTRICTED");

    public SkillSearchDocument {
        skillId = boundedRequired(skillId, "skillId", 128);
        name = boundedRequired(name, "name", 512);
        description = bounded(description, "description", 4_000);
        tags = immutableTags(tags);
        team = boundedRequired(team, "team", 256);
        category = boundedRequired(category, "category", 256);
        status = vocabulary(status, "status", STATUSES);
        risk = vocabulary(risk, "risk", RISKS);
        latestVersion = boundedRequired(latestVersion, "latestVersion", 128);
        visibility = vocabulary(visibility, "visibility", VISIBILITIES);
        ownerTeamId = bounded(ownerTeamId, "ownerTeamId", 128);
    }

    private static List<String> immutableTags(List<String> value) {
        if (value == null || value.size() > 64) {
            throw new IllegalArgumentException("tags must contain at most 64 values");
        }
        return List.copyOf(value).stream().map(tag -> boundedRequired(tag, "tag", 128)).toList();
    }

    static String boundedRequired(String value, String field, int maxLength) {
        String normalized = bounded(value, field, maxLength);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    static String bounded(String value, String field, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength || normalized.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(field + " is too long or contains invalid characters");
        }
        return normalized;
    }

    static String vocabulary(String value, String field, Set<String> allowed) {
        String normalized = boundedRequired(value, field, 32);
        String candidate = allowed.contains(normalized) ? normalized : normalized.toLowerCase(Locale.ROOT);
        if (!allowed.contains(candidate)) {
            throw new IllegalArgumentException("unsupported " + field);
        }
        return candidate;
    }
}
