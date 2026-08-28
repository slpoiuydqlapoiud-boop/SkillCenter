package com.huawei.skillcenter.quality;

public record OptimizationSuggestionDispositionRequest(String status, String note,
                                                        String evidenceType, String evidenceId) {
    public OptimizationSuggestionDispositionRequest(String status, String note) {
        this(status, note, "NONE", "");
    }

    public String normalizedStatus() {
        return OptimizationSuggestionDisposition.normalizeStatus(status);
    }

    public String normalizedNote() {
        String value = note == null ? "" : note.trim();
        if (value.length() > 500) {
            throw new IllegalArgumentException("note must not exceed 500 characters");
        }
        return value;
    }

    public String normalizedEvidenceType() {
        return OptimizationSuggestionDisposition.normalizeEvidenceType(evidenceType);
    }

    public String normalizedEvidenceId() {
        String type = normalizedEvidenceType();
        return OptimizationSuggestionDisposition.normalizeEvidenceId(type, evidenceId);
    }
}
