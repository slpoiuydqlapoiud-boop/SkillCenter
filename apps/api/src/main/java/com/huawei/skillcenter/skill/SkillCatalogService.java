package com.huawei.skillcenter.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class SkillCatalogService {
    private final SkillRepository repository;
    private final GovernanceStore governanceStore;
    private final ObjectMapper objectMapper;

    public SkillCatalogService(SkillRepository repository) {
        this(repository, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillCatalogService(SkillRepository repository, GovernanceStore governanceStore, ObjectMapper objectMapper) {
        this.repository = repository;
        this.governanceStore = governanceStore;
        this.objectMapper = objectMapper;
    }

    public PageResult<SkillSummary> list(SkillQuery query) {
        PageResult<SkillRecord> page = governedPage(query);
        return new PageResult<>(page.items().stream().map(SkillSummary::from).toList(), page.page(), page.pageSize(), page.total());
    }

    public SkillRecord detail(String skillId) {
        if (governanceStore != null) {
            Optional<SkillRecord> governed = governedRecords(new SkillQuery("", "", "", "", 1, 50)).stream()
                    .filter(skill -> skill.id().equals(skillId))
                    .findFirst();
            if (governed.isPresent()) {
                return governed.get();
            }
            boolean governedSkill = governanceStore.snapshot().versions().stream()
                    .anyMatch(version -> skillId.equals(version.skillId()));
            if (governedSkill) {
                throw new SkillNotFoundException(skillId);
            }
        }
        return repository.findDetail(skillId).orElseThrow(() -> new SkillNotFoundException(skillId));
    }

    public String content(String skillId) {
        if (governanceStore != null) {
            Optional<SkillVersion> version = governanceStore.snapshot().versions().stream()
                    .filter(candidate -> skillId.equals(candidate.skillId()))
                    .filter(candidate -> "published".equalsIgnoreCase(candidate.status())
                            || "deprecated".equalsIgnoreCase(candidate.status()))
                    .max(Comparator.comparing(SkillVersion::uploadedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
            if (version.isPresent()) {
                return readSkillMarkdown(version.get());
            }
            boolean governedSkill = governanceStore.snapshot().versions().stream()
                    .anyMatch(candidate -> skillId.equals(candidate.skillId()));
            if (governedSkill) {
                throw new SkillNotFoundException(skillId);
            }
        }
        if (repository.findDetail(skillId).isPresent()) {
            return "";
        }
        throw new SkillNotFoundException(skillId);
    }

    private PageResult<SkillRecord> governedPage(SkillQuery query) {
        if (governanceStore == null) {
            return repository.findPublished(query);
        }
        if (unknownGovernanceCategory(query.category())) {
            return new PageResult<>(List.of(), query.page(), query.pageSize(), 0);
        }
        List<SkillRecord> filtered = governedRecords(query).stream()
                .filter(matchesText(query.query()))
                .filter(matches(query.category(), SkillRecord::category))
                .filter(matches(query.status(), SkillRecord::status))
                .filter(matchesRisk(query.risk()))
                .sorted(SkillSort.comparator(query.sort()))
                .toList();
        int from = Math.min((query.page() - 1) * query.pageSize(), filtered.size());
        int to = Math.min(from + query.pageSize(), filtered.size());
        return new PageResult<>(filtered.subList(from, to), query.page(), query.pageSize(), filtered.size());
    }

    private boolean unknownGovernanceCategory(String category) {
        if (category == null || category.isBlank() || "all".equalsIgnoreCase(category)
                || governanceStore.snapshot().configuration().categories().isEmpty()) {
            return false;
        }
        return governanceStore.snapshot().configuration().categories().stream()
                .filter(item -> !"active".equalsIgnoreCase(item.status()))
                .anyMatch(item -> category.equalsIgnoreCase(item.code()) || category.equalsIgnoreCase(item.displayName()));
    }

    private List<SkillRecord> governedRecords(SkillQuery query) {
        Map<String, SkillRecord> records = new LinkedHashMap<>();
        int page = 1;
        PageResult<SkillRecord> published;
        do {
            published = repository.findPublished(new SkillQuery("", "", "", "", page, 50));
            published.items().forEach(skill -> records.put(skill.id(), skill));
            page += 1;
        } while (!published.items().isEmpty() && records.size() < published.total()
                && page <= Math.ceil((double) published.total() / 50));
        for (SkillVersion version : governanceStore.snapshot().versions()) {
            if ("withdrawn".equalsIgnoreCase(version.status())
                    || !Set.of("published", "deprecated").contains(version.status().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (version.artifactPath() != null && !version.artifactPath().isBlank()) {
                records.put(version.skillId(), readUploadedSkill(version));
            } else {
                SkillRecord existing = records.get(version.skillId());
                if (existing != null && version.version().equals(existing.version())) {
                    records.put(version.skillId(), withLifecycle(existing, version));
                }
            }
        }
        return new ArrayList<>(records.values());
    }

    private SkillRecord readUploadedSkill(SkillVersion version) {
        try (ZipFile zip = new ZipFile(Path.of(version.artifactPath()).toFile())) {
            ZipEntry skillEntry = zip.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().endsWith("/skill.json"))
                    .findFirst().orElse(null);
            if (skillEntry == null) {
                return fallback(version);
            }
            JsonNode node = objectMapper.readTree(zip.getInputStream(skillEntry));
            if (!version.skillId().equals(node.path("id").asText())) {
                return fallback(version);
            }
            String publishedAt = version.publishedAt() == null ? "" : version.publishedAt().toString();
            List<String> languages = strings(node.path("language"));
            List<String> maintainers = strings(node.path("maintainers"));
            JsonNode permissions = node.path("permissions");
            String permissionSummary = "workspace=" + permissions.path("workspace").asText("none")
                    + ", network=" + permissions.path("network").asText("none")
                    + ", shell=" + permissions.path("shell").asText("none");
            return new SkillRecord(version.skillId(), node.path("name").asText(version.skillId()), version.version(),
                    node.path("description").asText(""), node.path("category").asText("other"), strings(node.path("tags")),
                    risk(permissions), riskTone(permissions), node.path("ownerTeam").asText(version.uploadedBy()),
                    maintainers.isEmpty() ? version.uploadedBy() : maintainers.get(0), node.path("icon").asText(""),
                    "blue", version.status(), publishedAt, publishedAt, languages.isEmpty() ? "" : languages.get(0),
                    String.join(" / ", languages), permissionSummary, List.of(), strings(node.path("recommendedScenarios")),
                    strings(node.path("unsupportedScenarios")), node.path("releaseNotes").asText(""), "", "", List.of(),
                    new SkillMetrics(0, 0, 0, 0, 0));
        } catch (IOException | RuntimeException ignored) {
            return fallback(version);
        }
    }

    private String readSkillMarkdown(SkillVersion version) {
        if (version.artifactPath() == null || version.artifactPath().isBlank()) {
            return "";
        }
        try (ZipFile zip = new ZipFile(Path.of(version.artifactPath()).toFile())) {
            ZipEntry skillEntry = zip.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().endsWith("/SKILL.md"))
                    .findFirst().orElse(null);
            if (skillEntry == null) {
                return "";
            }
            return new String(zip.getInputStream(skillEntry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private SkillRecord fallback(SkillVersion version) {
        String timestamp = version.publishedAt() == null ? "" : version.publishedAt().toString();
        return new SkillRecord(version.skillId(), version.skillId(), version.version(), "", "other", List.of(),
                "low", "low", version.uploadedBy(), version.uploadedBy(), "", "blue", version.status(), timestamp,
                timestamp, "", "", "", List.of(), List.of(), List.of(), "", "", "", List.of(),
                new SkillMetrics(0, 0, 0, 0, 0));
    }

    private SkillRecord withLifecycle(SkillRecord existing, SkillVersion version) {
        return new SkillRecord(existing.id(), existing.name(), version.version(), existing.description(), existing.category(),
                existing.tags(), existing.risk(), existing.riskTone(), existing.team(), existing.owner(), existing.icon(),
                existing.iconTone(), version.status(), existing.lastUpdated(), existing.publishedAt(), existing.language(),
                existing.supportedLanguage(), existing.permissionSummary(), existing.capabilities(), existing.suitableFor(),
                existing.unsuitableFor(), existing.value(), existing.exampleInput(), existing.exampleOutput(),
                existing.collection(), existing.metrics());
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private String risk(JsonNode permissions) {
        if ("external".equals(permissions.path("network").asText())
                || "write".equals(permissions.path("workspace").asText())
                || "unrestricted".equals(permissions.path("shell").asText())) {
            return "high";
        }
        if ("internal".equals(permissions.path("network").asText())
                || "restricted".equals(permissions.path("shell").asText())) {
            return "medium";
        }
        return "low";
    }

    private String riskTone(JsonNode permissions) {
        return risk(permissions);
    }

    private Predicate<SkillRecord> matchesText(String query) {
        if (query == null || query.isBlank()) {
            return skill -> true;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return skill -> java.util.stream.Stream.of(skill.name(), skill.description(), skill.team(), skill.owner())
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
