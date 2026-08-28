package com.huawei.skillcenter.quality;

import java.util.List;
import java.time.Instant;

public record OptimizationSuggestion(
        String id,
        String skillId,
        String version,
        String severity,
        String category,
        String title,
        List<String> evidence,
        String recommendedAction,
        String dispositionStatus,
        String dispositionNote,
        String dispositionBy,
        Instant dispositionAt,
        String dispositionEvidenceType,
        String dispositionEvidenceId,
        String dispositionEvidenceStatus
) {
    public OptimizationSuggestion(String id, String skillId, String version, String severity,
                                   String category, String title, List<String> evidence,
                                   String recommendedAction) {
        this(id, skillId, version, severity, category, title, evidence, recommendedAction,
                OptimizationSuggestionDisposition.OPEN, "", null, null,
                OptimizationSuggestionDisposition.NONE, "", "NOT_LINKED");
    }

    public OptimizationSuggestion(String id, String skillId, String version, String severity,
                                  String category, String title, List<String> evidence,
                                  String recommendedAction, String dispositionStatus, String dispositionNote,
                                  String dispositionBy, Instant dispositionAt) {
        this(id, skillId, version, severity, category, title, evidence, recommendedAction,
                dispositionStatus, dispositionNote, dispositionBy, dispositionAt,
                OptimizationSuggestionDisposition.NONE, "", "NOT_LINKED");
    }

    public OptimizationSuggestion {
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        dispositionStatus = dispositionStatus == null || dispositionStatus.isBlank()
                ? OptimizationSuggestionDisposition.OPEN : dispositionStatus;
        dispositionNote = dispositionNote == null ? "" : dispositionNote;
        dispositionEvidenceType = dispositionEvidenceType == null || dispositionEvidenceType.isBlank()
                ? OptimizationSuggestionDisposition.NONE : dispositionEvidenceType;
        dispositionEvidenceId = dispositionEvidenceId == null ? "" : dispositionEvidenceId;
        dispositionEvidenceStatus = dispositionEvidenceStatus == null || dispositionEvidenceStatus.isBlank()
                ? (OptimizationSuggestionDisposition.NONE.equals(dispositionEvidenceType) ? "NOT_LINKED" : "AVAILABLE")
                : dispositionEvidenceStatus;
    }

    public OptimizationSuggestion withDisposition(OptimizationSuggestionDisposition disposition) {
        if (disposition == null) {
            return this;
        }
        return new OptimizationSuggestion(id, skillId, version, severity, category, title, evidence,
                recommendedAction, disposition.status(), disposition.note(), disposition.actorId(), disposition.updatedAt(),
                disposition.evidenceType(), disposition.evidenceId(),
                OptimizationSuggestionDisposition.NONE.equals(disposition.evidenceType()) ? "NOT_LINKED" : "AVAILABLE");
    }

    public OptimizationSuggestion withDisposition(OptimizationSuggestionDisposition disposition,
                                                   boolean evidenceAvailable) {
        if (disposition == null) return this;
        return new OptimizationSuggestion(id, skillId, version, severity, category, title, evidence,
                recommendedAction, disposition.status(), disposition.note(), disposition.actorId(), disposition.updatedAt(),
                disposition.evidenceType(), disposition.evidenceId(),
                OptimizationSuggestionDisposition.NONE.equals(disposition.evidenceType())
                        ? "NOT_LINKED" : evidenceAvailable ? "AVAILABLE" : "EXPIRED");
    }
}
