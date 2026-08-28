package com.huawei.skillcenter.search;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic, in-process search index for the bounded search document contract.
 */
public final class JsonSkillSearchIndex implements SkillSearchIndex {
    private static final int MAX_CANDIDATES = 5_000;
    private static final int MAX_PREFIX_LENGTH = 64;
    private static final List<Field> FIELDS = List.of(
            new Field("id", 100),
            new Field("name", 80),
            new Field("tags", 60),
            new Field("description", 40),
            new Field("team", 20),
            new Field("category", 20));

    private volatile Snapshot snapshot = new Snapshot(Map.of(), "", 0);
    private volatile String state = "NOT_READY";

    @Override
    public SkillSearchIndexStatus status() {
        Snapshot current = snapshot;
        return new SkillSearchIndexStatus(state, current.revision(), current.documents().size(), current.sourceHash(),
                Integer.toString(current.revision()));
    }

    @Override
    public synchronized SkillSearchRebuildResult rebuild(List<SkillSearchDocument> documents, String sourceHash) {
        String validatedHash = SkillSearchDocument.boundedRequired(sourceHash, "sourceHash", 256);
        if (documents == null) {
            throw new IllegalArgumentException("documents is required");
        }

        Snapshot current = snapshot;
        if (validatedHash.equals(current.sourceHash())) {
            state = "READY";
            return result(current);
        }

        Map<String, IndexedDocument> next = new LinkedHashMap<>();
        for (SkillSearchDocument document : documents) {
            if (document == null) {
                throw new IllegalArgumentException("documents must not contain null");
            }
            IndexedDocument indexed = IndexedDocument.from(document);
            if (next.putIfAbsent(document.skillId(), indexed) != null) {
                throw new IllegalArgumentException("documents must not contain duplicate skillId values");
            }
        }

        Snapshot committed = new Snapshot(Map.copyOf(next), validatedHash, current.revision() + 1);
        snapshot = committed;
        state = "READY";
        return result(committed);
    }

    @Override
    public synchronized void invalidate(String reason) {
        SkillSearchDocument.bounded(reason, "reason", 256);
        state = "STALE";
    }

    @Override
    public List<SkillSearchHit> search(SkillSearchQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query is required");
        }
        List<String> queryTokens = queryTokens(query.text());
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        return snapshot.documents().values().stream()
                .filter(indexed -> matchesFilters(indexed.document(), query))
                .map(indexed -> hit(indexed, queryTokens))
                .filter(java.util.Objects::nonNull)
                .sorted(hitComparator(snapshot.documents()))
                .limit(MAX_CANDIDATES)
                .toList();
    }

    private static SkillSearchRebuildResult result(Snapshot current) {
        return new SkillSearchRebuildResult(current.revision(), current.documents().size(), current.sourceHash(),
                Integer.toString(current.revision()));
    }

    private static boolean matchesFilters(SkillSearchDocument document, SkillSearchQuery query) {
        return (query.category().isEmpty() || normalize(document.category()).equals(normalize(query.category())))
                && (query.status().isEmpty() || document.status().equals(query.status()))
                && (query.risk().isEmpty() || document.risk().equals(query.risk()));
    }

    private static SkillSearchHit hit(IndexedDocument indexed, List<String> queryTokens) {
        List<String> matchedFields = new ArrayList<>();
        double score = 0;
        for (Field field : FIELDS) {
            if (queryTokens.stream().anyMatch(indexed.tokens(field.name())::contains)) {
                matchedFields.add(field.name());
                score += field.weight();
            }
        }
        boolean allTokensMatch = queryTokens.stream()
                .allMatch(token -> indexed.tokens().values().stream().anyMatch(tokens -> tokens.contains(token)));
        return allTokensMatch ? new SkillSearchHit(indexed.document().skillId(), score, matchedFields) : null;
    }

    private static Comparator<SkillSearchHit> hitComparator(Map<String, IndexedDocument> documents) {
        return Comparator.comparingDouble(SkillSearchHit::score).reversed()
                .thenComparing(hit -> statusPriority(documents.get(hit.skillId()).document().status()), Comparator.reverseOrder())
                .thenComparing(hit -> timestamp(documents.get(hit.skillId()).document().lastUpdated()), Comparator.reverseOrder())
                .thenComparing(SkillSearchHit::skillId);
    }

    private static int statusPriority(String status) {
        return switch (status) {
            case "published" -> 3;
            case "deprecated" -> 2;
            case "withdrawn" -> 1;
            default -> 0;
        };
    }

    private static Instant timestamp(Instant value) {
        return value == null ? Instant.MIN : value;
    }

    private static List<String> queryTokens(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return List.of(normalized.split(" "));
    }

    private static Set<String> tokens(String value) {
        String normalized = normalize(value);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (!normalized.isEmpty()) {
            addToken(result, normalized);
            for (String part : normalized.split("[^\\p{L}\\p{N}]+")) {
                addToken(result, part);
            }
        }
        return Set.copyOf(result);
    }

    private static void addToken(Set<String> target, String token) {
        if (token.isEmpty()) {
            return;
        }
        target.add(token);
        int prefixLength = Math.min(token.length(), MAX_PREFIX_LENGTH);
        for (int length = 1; length <= prefixLength; length++) {
            target.add(token.substring(0, length));
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        boolean previousWhitespace = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (!previousWhitespace && !normalized.isEmpty()) {
                    normalized.append(' ');
                }
                previousWhitespace = true;
            } else {
                normalized.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
            offset += Character.charCount(codePoint);
        }
        return Normalizer.normalize(normalized.toString().strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private record Field(String name, int weight) {
    }

    private record Snapshot(Map<String, IndexedDocument> documents, String sourceHash, int revision) {
    }

    private record IndexedDocument(SkillSearchDocument document, Map<String, Set<String>> tokens) {
        private static IndexedDocument from(SkillSearchDocument document) {
            Map<String, Set<String>> tokens = new LinkedHashMap<>();
            tokens.put("id", JsonSkillSearchIndex.tokens(document.skillId()));
            tokens.put("name", JsonSkillSearchIndex.tokens(document.name()));
            tokens.put("tags", document.tags().stream()
                    .flatMap(tag -> JsonSkillSearchIndex.tokens(tag).stream())
                    .collect(Collectors.toUnmodifiableSet()));
            tokens.put("description", JsonSkillSearchIndex.tokens(document.description()));
            tokens.put("team", JsonSkillSearchIndex.tokens(document.team()));
            tokens.put("category", JsonSkillSearchIndex.tokens(document.category()));
            return new IndexedDocument(document, Map.copyOf(tokens));
        }

        private Set<String> tokens(String field) {
            return tokens.get(field);
        }
    }
}
