package com.huawei.skillcenter.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillNotVisibleException;
import com.huawei.skillcenter.access.SkillScopeNotFoundException;
import com.huawei.skillcenter.access.SkillVisibilityContext;
import com.huawei.skillcenter.distribution.ArtifactStorage;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.search.SkillSearchDocumentSource;
import com.huawei.skillcenter.search.SkillSearchHit;
import com.huawei.skillcenter.search.SkillSearchIndex;
import com.huawei.skillcenter.search.SkillSearchQuery;
import com.huawei.skillcenter.search.SkillSearchRefreshCoordinator;
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
import java.util.zip.ZipInputStream;

@Service
public class SkillCatalogService {
    private static final Comparator<SkillVersion> CURRENT_GOVERNANCE_VERSION = Comparator
            .comparing(SkillCatalogService::lifecycleTimestamp)
            .thenComparing(SkillVersion::version, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(SkillVersion::packageId, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final SkillRepository repository;
    private final GovernanceStore governanceStore;
    private final ObjectMapper objectMapper;
    private final SkillAuthorizationService authorizationService;
    private final ArtifactStorage artifactStorage;
    private final SkillSearchIndex searchIndex;
    private final SkillSearchDocumentSource searchSource;
    private final SkillSearchRefreshCoordinator searchRefreshCoordinator;

    public SkillCatalogService(SkillRepository repository) {
        this(repository, null, null, null, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillCatalogService(SkillRepository repository, GovernanceStore governanceStore, ObjectMapper objectMapper,
                               SkillAuthorizationService authorizationService, ArtifactStorage artifactStorage,
                               SkillSearchIndex searchIndex, SkillSearchDocumentSource searchSource,
                               SkillSearchRefreshCoordinator searchRefreshCoordinator) {
        this.repository = repository;
        this.governanceStore = governanceStore;
        this.objectMapper = objectMapper;
        this.authorizationService = authorizationService;
        this.artifactStorage = artifactStorage;
        this.searchIndex = searchIndex;
        this.searchSource = searchSource;
        this.searchRefreshCoordinator = searchRefreshCoordinator;
    }

    public SkillCatalogService(SkillRepository repository, GovernanceStore governanceStore, ObjectMapper objectMapper,
                               SkillAuthorizationService authorizationService, ArtifactStorage artifactStorage) {
        this(repository, governanceStore, objectMapper, authorizationService, artifactStorage, null, null, null);
    }

    public SkillCatalogService(SkillRepository repository, GovernanceStore governanceStore, ObjectMapper objectMapper,
                               SkillAuthorizationService authorizationService) {
        this(repository, governanceStore, objectMapper, authorizationService, null, null, null, null);
    }

    public SkillCatalogService(SkillRepository repository, GovernanceStore governanceStore, ObjectMapper objectMapper) {
        this(repository, governanceStore, objectMapper, null, null, null, null, null);
    }

    public PageResult<SkillSummary> list(SkillQuery query) {
        PageResult<SkillRecord> page = governedPage(query);
        return new PageResult<>(page.items().stream().map(SkillSummary::from).toList(), page.page(), page.pageSize(), page.total());
    }

    public PageResult<SkillSummary> list(SkillQuery query, Actor actor) {
        if (indexedActorAwarePathAvailable()) {
            return indexedActorAwarePage(query, actor);
        }
        PageResult<SkillRecord> page = actorAwarePage(query, actor);
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

    public SkillRecord detail(String skillId, Actor actor) {
        requireCatalogVisible(skillId, actor, SkillVisibilityContext.CATALOG);
        return detail(skillId);
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

    public String content(String skillId, Actor actor) {
        requireCatalogVisible(skillId, actor, SkillVisibilityContext.CONTENT);
        return content(skillId);
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

    private PageResult<SkillRecord> actorAwarePage(SkillQuery query, Actor actor) {
        if (authorizationService == null) {
            return governedPage(query);
        }
        if (governanceStore == null) {
            return repository.findPublished(query);
        }
        if (unknownGovernanceCategory(query.category())) {
            return new PageResult<>(List.of(), query.page(), query.pageSize(), 0);
        }
        List<SkillRecord> filtered = governedRecords(query).stream()
                .filter(skill -> visibleInCatalog(skill.id(), actor))
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

    private boolean indexedActorAwarePathAvailable() {
        return governanceStore != null && authorizationService != null && searchIndex != null && searchSource != null
                && searchRefreshCoordinator != null;
    }

    private PageResult<SkillSummary> indexedActorAwarePage(SkillQuery query, Actor actor) {
        if (unknownGovernanceCategory(query.category())) {
            return new PageResult<>(List.of(), query.page(), query.pageSize(), 0);
        }
        searchRefreshCoordinator.ensureReady();
        boolean textQuery = query.query() != null && !query.query().isBlank();
        List<IndexedSkill> visible = new ArrayList<>();
        for (SkillSearchHit hit : searchIndex.search(searchQuery(query))) {
            Optional<SkillRecord> resolved = searchSource.findRecord(hit.skillId());
            if (resolved.isEmpty() || !matchesCurrentGovernanceVersion(hit.skillId(), resolved.get())
                    || !visibleInIndexedCatalog(hit.skillId(), actor)) {
                continue;
            }
            visible.add(new IndexedSkill(resolved.get(), textQuery
                    ? new SkillSearchMetadata(hit.score(), hit.matchedFields()) : null));
        }
        if (!"relevance".equals(query.sort())) {
            visible.sort(Comparator.comparing(IndexedSkill::record, SkillSort.comparator(query.sort())));
        }
        int from = Math.min((query.page() - 1) * query.pageSize(), visible.size());
        int to = Math.min(from + query.pageSize(), visible.size());
        List<SkillSummary> items = visible.subList(from, to).stream()
                .map(candidate -> summary(candidate.record(), candidate.search()))
                .toList();
        return new PageResult<>(items, query.page(), query.pageSize(), visible.size());
    }

    private SkillSearchQuery searchQuery(SkillQuery query) {
        return new SkillSearchQuery(query.query(), searchFilter(query.category()), searchFilter(query.status()),
                searchFilter(query.risk()), query.sort());
    }

    private String searchFilter(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value) ? "" : value;
    }

    private boolean catalogStatus(String status) {
        return status != null && Set.of("published", "deprecated").contains(status.toLowerCase(Locale.ROOT));
    }

    private boolean matchesCurrentGovernanceVersion(String skillId, SkillRecord record) {
        if (record.id() == null || !skillId.equals(record.id())) {
            return false;
        }
        return governanceStore.snapshot().versions().stream()
                .filter(version -> skillId.equals(version.skillId()))
                .max(CURRENT_GOVERNANCE_VERSION)
                .filter(version -> catalogStatus(version.status()))
                .filter(version -> version.version() != null && version.version().equals(record.version()))
                .filter(version -> normalizedStatus(version.status()).equals(normalizedStatus(record.status())))
                .isPresent();
    }

    private static Instant lifecycleTimestamp(SkillVersion version) {
        Instant uploadedAt = version.uploadedAt() == null ? Instant.EPOCH : version.uploadedAt();
        if (version.publishedAt() == null || !version.publishedAt().isAfter(uploadedAt)) {
            return uploadedAt;
        }
        return version.publishedAt();
    }

    private String normalizedStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }

    private boolean visibleInIndexedCatalog(String skillId, Actor actor) {
        try {
            authorizationService.requireVisible(skillId, actor, SkillVisibilityContext.CATALOG);
            return true;
        } catch (SkillNotVisibleException hidden) {
            return false;
        }
    }

    private SkillSummary summary(SkillRecord skill, SkillSearchMetadata search) {
        return new SkillSummary(skill.id(), skill.name(), skill.version(), skill.description(), skill.category(),
                skill.tags(), skill.risk(), skill.riskTone(), skill.team(), skill.owner(), skill.icon(), skill.iconTone(),
                skill.status(), skill.lastUpdated(), skill.metrics(), search);
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

    private boolean visibleInCatalog(String skillId, Actor actor) {
        try {
            authorizationService.requireVisible(skillId, actor, SkillVisibilityContext.CATALOG);
            return true;
        } catch (SkillNotVisibleException hidden) {
            return isHistoricalRepositorySkill(skillId);
        }
    }

    private void requireCatalogVisible(String skillId, Actor actor, SkillVisibilityContext context) {
        if (authorizationService == null || isHistoricalRepositorySkill(skillId)) {
            return;
        }
        authorizationService.requireVisible(skillId, actor, context);
    }

    private boolean isHistoricalRepositorySkill(String skillId) {
        if (authorizationService == null || repository.findDetail(skillId).isEmpty()) {
            return false;
        }
        try {
            authorizationService.effectiveScope(skillId);
            return false;
        } catch (SkillScopeNotFoundException missing) {
            return true;
        }
    }

    private SkillRecord readUploadedSkill(SkillVersion version) {
        try {
            byte[] skillJson = readEntry(version, "/skill.json");
            if (skillJson == null) {
                return fallback(version);
            }
            JsonNode node = objectMapper.readTree(skillJson);
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
        try {
            byte[] markdown = readEntry(version, "/SKILL.md");
            return markdown == null ? "" : new String(markdown, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private byte[] readEntry(SkillVersion version, String suffix) throws IOException {
        if (artifactStorage != null) {
            ArtifactStorage.ArtifactResource artifact = artifactStorage.open(
                    version.artifactPath(), version.sha256());
            try (var input = artifact.resource().getInputStream();
                 ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && entry.getName().endsWith(suffix)) {
                        return zip.readAllBytes();
                    }
                }
                return null;
            }
        }
        try (ZipFile zip = new ZipFile(Path.of(version.artifactPath()).toFile())) {
            ZipEntry entry = zip.stream()
                    .filter(candidate -> !candidate.isDirectory() && candidate.getName().endsWith(suffix))
                    .findFirst().orElse(null);
            return entry == null ? null : zip.getInputStream(entry).readAllBytes();
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

    private record IndexedSkill(SkillRecord record, SkillSearchMetadata search) {
    }
}
