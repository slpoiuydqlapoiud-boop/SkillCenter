package com.huawei.skillcenter.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Repository
public class InMemorySkillRepository implements SkillRepository {
    private final List<SkillRecord> skills;

    public InMemorySkillRepository(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource("skills.json").getInputStream()) {
            this.skills = List.copyOf(objectMapper.readValue(input, new TypeReference<List<SkillRecord>>() {
            }));
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 Skill 种子数据", exception);
        }
    }

    @Override
    public PageResult<SkillRecord> findPublished(SkillQuery query) {
        Stream<SkillRecord> stream = skills.stream().filter(skill -> "published".equalsIgnoreCase(skill.status()));
        stream = stream.filter(matchesText(query.query()))
                .filter(matches(query.category(), SkillRecord::category))
                .filter(matches(query.status(), SkillRecord::status))
                .filter(matchesRisk(query.risk()));
        List<SkillRecord> filtered = stream.sorted(SkillSort.comparator(query.sort())).toList();
        int from = Math.min((query.page() - 1) * query.pageSize(), filtered.size());
        int to = Math.min(from + query.pageSize(), filtered.size());
        return new PageResult<>(filtered.subList(from, to), query.page(), query.pageSize(), filtered.size());
    }

    @Override
    public Optional<SkillRecord> findDetail(String skillId) {
        return skills.stream().filter(skill -> skill.id().equals(skillId) && "published".equalsIgnoreCase(skill.status())).findFirst();
    }

    private Predicate<SkillRecord> matchesText(String query) {
        if (query == null || query.isBlank()) {
            return skill -> true;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return skill -> Stream.of(skill.name(), skill.description(), skill.team(), skill.owner())
                .anyMatch(value -> value != null && value.toLowerCase(Locale.ROOT).contains(normalized))
                || skill.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(normalized));
    }

    private Predicate<SkillRecord> matches(String expected, java.util.function.Function<SkillRecord, String> accessor) {
        if (expected == null || expected.isBlank() || "all".equalsIgnoreCase(expected)) {
            return skill -> true;
        }
        return skill -> expected.equalsIgnoreCase(accessor.apply(skill));
    }

    private Predicate<SkillRecord> matchesRisk(String expected) {
        if (expected == null || expected.isBlank() || "all".equalsIgnoreCase(expected)) {
            return skill -> true;
        }
        return skill -> expected.equalsIgnoreCase(skill.risk())
                || expected.replace("风险", "").equalsIgnoreCase(skill.risk().replace("风险", ""));
    }
}
