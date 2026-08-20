package com.huawei.skillcenter.skill;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Locale;

final class SkillSort {
    private SkillSort() {
    }

    static String normalize(String sort) {
        if (sort == null) {
            return "updated";
        }
        String value = sort.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "downloads", "calls", "favorites", "updated" -> value;
            default -> "updated";
        };
    }

    static Comparator<SkillRecord> comparator(String sort) {
        Comparator<SkillRecord> metric = switch (normalize(sort)) {
            case "downloads" -> Comparator.comparingInt(SkillSort::downloads);
            case "calls" -> Comparator.comparingInt(SkillSort::calls);
            case "favorites" -> Comparator.comparingInt(SkillSort::favorites);
            default -> Comparator.comparingLong(SkillSort::updatedAt);
        };
        return metric.reversed().thenComparing(SkillRecord::id, Comparator.nullsLast(String::compareTo));
    }

    private static int downloads(SkillRecord skill) {
        return skill.metrics() == null ? 0 : skill.metrics().installs();
    }

    private static int calls(SkillRecord skill) {
        return skill.metrics() == null ? 0 : skill.metrics().calls();
    }

    private static int favorites(SkillRecord skill) {
        return skill.metrics() == null ? 0 : skill.metrics().favorites();
    }

    private static long updatedAt(SkillRecord skill) {
        String value = skill.lastUpdated() == null || skill.lastUpdated().isBlank()
                ? skill.publishedAt() : skill.lastUpdated();
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return LocalDate.parse(value.substring(0, Math.min(10, value.length()))).toEpochDay();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
