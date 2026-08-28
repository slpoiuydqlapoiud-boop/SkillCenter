package com.huawei.skillcenter.search;

import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.skill.PageResult;
import com.huawei.skillcenter.skill.SkillQuery;
import com.huawei.skillcenter.skill.SkillRecord;
import com.huawei.skillcenter.skill.SkillRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Builds bounded search documents from governed metadata without reading package content. */
public final class GovernedSkillSearchDocumentSource implements SkillSearchDocumentSource {
    private static final int PAGE_SIZE = 50;
    private static final Comparator<SkillVersion> LATEST_VERSION = Comparator
            .comparing(GovernedSkillSearchDocumentSource::versionTimestamp)
            .thenComparing(SkillVersion::version, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(SkillVersion::packageId, Comparator.nullsFirst(Comparator.naturalOrder()));
    private static final Comparator<SkillRecord> RECORD_ORDER = Comparator
            .comparing((SkillRecord record) -> normalize(record.version()))
            .thenComparing(record -> normalize(record.name()))
            .thenComparing(record -> normalize(record.description()))
            .thenComparing(record -> normalizedTags(record.tags()).toString())
            .thenComparing(record -> normalize(record.team()))
            .thenComparing(record -> normalize(record.category()))
            .thenComparing(record -> normalize(record.risk()))
            .thenComparing(record -> normalize(record.lastUpdated()))
            .thenComparing(record -> normalize(record.publishedAt()));

    private final GovernanceStore governance;
    private final SkillRepository repository;
    private final SkillSearchScopeProvider scopes;
    private final SkillSearchSourceRevisionProvider revisionProvider;
    private volatile Map<String, SkillRecord> cachedRecords = Map.of();

    public GovernedSkillSearchDocumentSource(GovernanceStore governance, SkillRepository repository) {
        this(governance, repository, SkillSearchScopeProvider.none(), SkillSearchSourceRevisionProvider.zero());
    }

    public GovernedSkillSearchDocumentSource(GovernanceStore governance,
                                             SkillRepository repository,
                                             SkillSearchScopeProvider scopes) {
        this(governance, repository, scopes, SkillSearchSourceRevisionProvider.zero());
    }

    public GovernedSkillSearchDocumentSource(GovernanceStore governance,
                                             SkillRepository repository,
                                             SkillSearchScopeProvider scopes,
                                             SkillSearchSourceRevisionProvider revisionProvider) {
        if (governance == null || repository == null || scopes == null || revisionProvider == null) {
            throw new IllegalArgumentException("governance, repository, scopes, and revisionProvider are required");
        }
        this.governance = governance;
        this.repository = repository;
        this.scopes = scopes;
        this.revisionProvider = revisionProvider;
    }

    @Override
    public SkillSearchDocumentSnapshot snapshot() {
        Map<String, SkillVersion> versions = selectedVersions();
        Map<String, List<SkillRecord>> records = recordsBySkillId(readAllRecords());
        TreeMap<String, SkillSearchDocument> documents = new TreeMap<>();
        TreeMap<String, SkillRecord> cache = new TreeMap<>();
        for (Map.Entry<String, SkillVersion> entry : versions.entrySet()) {
            SkillRecord record = selectRecord(entry.getValue(), records.get(entry.getKey()));
            if (record == null) {
                continue;
            }
            documents.put(entry.getKey(), document(entry.getValue(), record));
            cache.put(entry.getKey(), record);
        }
        List<SkillSearchDocument> ordered = List.copyOf(documents.values());
        cachedRecords = Map.copyOf(cache);
        long sourceRevision = revisionProvider.sourceRevision();
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision must be non-negative");
        }
        return new SkillSearchDocumentSnapshot(ordered, hash(ordered), sourceRevision);
    }

    @Override
    public Optional<SkillRecord> findRecord(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return Optional.empty();
        }
        String normalized = SkillSearchDocument.boundedRequired(skillId, "skillId", 128);
        SkillRecord cached = cachedRecords.get(normalized);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (!selectedVersions().containsKey(normalized)) {
            return Optional.empty();
        }
        return repository.findDetail(normalized);
    }

    private Map<String, SkillVersion> selectedVersions() {
        TreeMap<String, SkillVersion> selected = new TreeMap<>();
        for (SkillVersion version : governance.snapshot().versions()) {
            if (!includedStatus(version.status())) {
                continue;
            }
            selected.merge(version.skillId(), version, (left, right) ->
                    LATEST_VERSION.compare(left, right) >= 0 ? left : right);
        }
        return selected;
    }

    private List<SkillRecord> readAllRecords() {
        List<SkillRecord> result = new ArrayList<>();
        for (int page = 1; ; page++) {
            PageResult<SkillRecord> response = repository.findPublished(new SkillQuery("", "", "", "", page, PAGE_SIZE));
            if (response == null || response.items() == null || response.items().isEmpty()) {
                break;
            }
            result.addAll(response.items());
            if ((long) page * PAGE_SIZE >= response.total()) {
                break;
            }
        }
        return result;
    }

    private static Map<String, List<SkillRecord>> recordsBySkillId(List<SkillRecord> records) {
        Map<String, List<SkillRecord>> result = new TreeMap<>();
        for (SkillRecord record : records) {
            if (record != null && record.id() != null && !record.id().isBlank()) {
                result.computeIfAbsent(record.id().strip(), ignored -> new ArrayList<>()).add(record);
            }
        }
        return result;
    }

    private static SkillRecord selectRecord(SkillVersion version, List<SkillRecord> candidates) {
        if (candidates == null) {
            return null;
        }
        return candidates.stream()
                .filter(record -> version.version().equals(record.version()))
                .sorted(RECORD_ORDER)
                .findFirst()
                .orElseGet(() -> candidates.stream()
                        .sorted(RECORD_ORDER)
                        .findFirst().orElse(null));
    }

    private SkillSearchDocument document(SkillVersion version, SkillRecord record) {
        var scope = scopes.find(version.skillId());
        String visibility = scope.map(SkillSearchScope::visibility).orElse("PUBLIC");
        String ownerTeamId = scope.map(value -> value.ownerTeamId()).orElse("");
        Instant publishedAt = version.publishedAt() == null ? timestamp(record.publishedAt(), versionTimestamp(version))
                : version.publishedAt();
        Instant lastUpdated = version.statusChangedAt() == null ? timestamp(record.lastUpdated(), versionTimestamp(version))
                : version.statusChangedAt();
        return new SkillSearchDocument(version.skillId(), required(record.name(), version.skillId()), normalize(record.description()),
                normalizedTags(record.tags()), required(record.team(), "unassigned"), required(record.category(), "other"),
                normalizedStatus(version.status()), normalizedRisk(record.risk()), lastUpdated, publishedAt,
                version.version(), visibility, ownerTeamId);
    }

    private static boolean includedStatus(String value) {
        String status = normalizedStatus(value);
        return "published".equals(status) || "deprecated".equals(status);
    }

    private static String normalizedStatus(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizedRisk(String value) {
        String candidate = value == null ? "low" : value.strip().toLowerCase(Locale.ROOT);
        return List.of("low", "medium", "high").contains(candidate) ? candidate : "low";
    }

    private static Instant versionTimestamp(SkillVersion version) {
        Instant uploadedAt = version.uploadedAt() == null ? Instant.EPOCH : version.uploadedAt();
        if (version.publishedAt() == null) {
            return uploadedAt;
        }
        return version.publishedAt().isAfter(uploadedAt) ? version.publishedAt() : uploadedAt;
    }

    private static Instant timestamp(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value.strip()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException invalid) {
                return fallback;
            }
        }
    }

    private static List<String> normalizedTags(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && !normalize(value).isEmpty()).map(GovernedSkillSearchDocumentSource::normalize)
                .distinct().sorted().toList();
    }

    private static String required(String value, String fallback) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+", " ").strip();
    }

    private static String hash(List<SkillSearchDocument> documents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (SkillSearchDocument document : documents) {
                add(digest, "skillId", document.skillId());
                add(digest, "name", document.name());
                add(digest, "description", document.description());
                add(digest, "tags", Integer.toString(document.tags().size()));
                for (String tag : document.tags().stream().map(GovernedSkillSearchDocumentSource::normalize).sorted().toList()) {
                    add(digest, "tag", tag);
                }
                add(digest, "team", document.team()); add(digest, "category", document.category());
                add(digest, "status", document.status()); add(digest, "risk", document.risk());
                add(digest, "lastUpdated", document.lastUpdated() == null ? "" : document.lastUpdated().toString());
                add(digest, "publishedAt", document.publishedAt() == null ? "" : document.publishedAt().toString());
                add(digest, "latestVersion", document.latestVersion()); add(digest, "visibility", document.visibility());
                add(digest, "ownerTeamId", document.ownerTeamId());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void add(MessageDigest digest, String field, String value) {
        byte[] fieldBytes = field.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = normalize(value).getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(fieldBytes.length).array());
        digest.update(fieldBytes);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(valueBytes.length).array());
        digest.update(valueBytes);
    }
}
