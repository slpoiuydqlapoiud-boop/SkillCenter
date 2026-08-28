package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public record OptimizationWorkItem(
        String workItemId,
        String skillId,
        String sourceVersion,
        String suggestionId,
        String suggestionTitle,
        String suggestionCategory,
        String suggestionSeverity,
        List<String> suggestionEvidence,
        String hypothesis,
        String ownerId,
        String status,
        String candidateVersion,
        String evidenceType,
        String evidenceId,
        String outcome,
        String dataSource,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        String suiteId,
        String suiteVersion,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt
) {
    public OptimizationWorkItem(String workItemId, String skillId, String sourceVersion, String suggestionId,
                                String suggestionTitle, String suggestionCategory, String suggestionSeverity,
                                List<String> suggestionEvidence, String hypothesis, String ownerId, String status,
                                String candidateVersion, String evidenceType, String evidenceId, String outcome,
                                String dataSource, String runtimeId, String mcpServerId, String llmProviderId,
                                String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this(workItemId, skillId, sourceVersion, suggestionId, suggestionTitle, suggestionCategory,
                suggestionSeverity, suggestionEvidence, hypothesis, ownerId, status, candidateVersion,
                evidenceType, evidenceId, outcome, dataSource, runtimeId, mcpServerId, llmProviderId,
                "", "", createdBy, createdAt, updatedBy, updatedAt);
    }

    public static final String NONE = "NONE";
    public static final String EVALUATION_RUN = "EVALUATION_RUN";
    public static final String QUALITY_SNAPSHOT = "QUALITY_SNAPSHOT";
    public static final String BENCHMARK = "BENCHMARK";
    public static final String POST_RELEASE_ASSESSMENT = "POST_RELEASE_ASSESSMENT";

    public OptimizationWorkItem {
        requireIdentifier(workItemId, "workItemId");
        requireIdentifier(skillId, "skillId");
        requireText(sourceVersion, "sourceVersion", 128);
        requireIdentifier(suggestionId, "suggestionId");
        requireText(suggestionTitle, "suggestionTitle", 200);
        requireText(suggestionCategory, "suggestionCategory", 64);
        requireText(suggestionSeverity, "suggestionSeverity", 32);
        suggestionEvidence = normalizeEvidence(suggestionEvidence);
        requireText(hypothesis, "hypothesis", 500);
        requireIdentifier(ownerId, "ownerId");
        status = OptimizationWorkItemStatus.normalize(status);
        candidateVersion = normalizeOptional(candidateVersion, 128);
        evidenceType = normalizeEvidenceType(evidenceType);
        evidenceId = normalizeEvidenceId(evidenceType, evidenceId);
        outcome = normalizeOptional(outcome, 500);
        dataSource = normalizeDataSource(dataSource);
        runtimeId = normalizeEnvironment(runtimeId, "runtimeId");
        mcpServerId = normalizeEnvironment(mcpServerId, "mcpServerId");
        llmProviderId = normalizeEnvironment(llmProviderId, "llmProviderId");
        suiteId = normalizeSuiteIdentifier(suiteId, "suiteId");
        suiteVersion = normalizeSuiteIdentifier(suiteVersion, "suiteVersion");
        if (suiteId.isBlank() != suiteVersion.isBlank()) {
            throw new IllegalArgumentException("suiteId and suiteVersion must be provided together");
        }
        requireIdentifier(createdBy, "createdBy");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        requireIdentifier(updatedBy, "updatedBy");
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (OptimizationWorkItemStatus.READY_FOR_EVALUATION.equals(status)
                || OptimizationWorkItemStatus.COMPLETED.equals(status)) {
            requireText(candidateVersion, "candidateVersion", 128);
        }
        if (OptimizationWorkItemStatus.COMPLETED.equals(status)) {
            if (NONE.equals(evidenceType)) throw new IllegalArgumentException("completed work item requires evidence");
            requireText(outcome, "outcome", 500);
        }
        if (OptimizationWorkItemStatus.ABANDONED.equals(status)) {
            requireText(outcome, "outcome", 500);
        }
    }

    private static List<String> normalizeEvidence(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 20) throw new IllegalArgumentException("suggestionEvidence must contain at most 20 items");
        return values.stream().map(value -> {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("suggestionEvidence contains an invalid item");
            }
            return value.trim();
        }).toList();
    }

    private static String normalizeEvidenceType(String value) {
        String normalized = value == null || value.isBlank() ? NONE : value.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of(NONE, EVALUATION_RUN, QUALITY_SNAPSHOT, BENCHMARK, POST_RELEASE_ASSESSMENT).contains(normalized)) {
            throw new IllegalArgumentException("evidenceType must be NONE, EVALUATION_RUN, QUALITY_SNAPSHOT, BENCHMARK or POST_RELEASE_ASSESSMENT");
        }
        return normalized;
    }

    private static String normalizeEvidenceId(String type, String value) {
        String normalized = value == null ? "" : value.trim();
        if (NONE.equals(type)) {
            if (!normalized.isEmpty()) throw new IllegalArgumentException("evidenceId must be blank when evidenceType is NONE");
            return "";
        }
        requireIdentifier(normalized, "evidenceId");
        return normalized;
    }

    private static String normalizeDataSource(String value) {
        String normalized = value == null || value.isBlank() ? "all" : value.trim().toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("mock", "production", "all").contains(normalized)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
        return normalized;
    }

    private static String normalizeEnvironment(String value, String field) {
        String normalized = normalizeOptional(value, 128);
        if (!normalized.isEmpty() && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String normalizeSuiteIdentifier(String value, String field) {
        String normalized = normalizeOptional(value, 128);
        if (!normalized.isBlank()) requireIdentifier(normalized, field);
        return normalized;
    }

    private static String normalizeOptional(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("value exceeds maximum length");
        return normalized;
    }

    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        if (value.trim().length() > max) throw new IllegalArgumentException(field + " exceeds maximum length");
    }

    private static void requireIdentifier(String value, String field) {
        requireText(value, field, 128);
        if (!value.trim().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }
}
