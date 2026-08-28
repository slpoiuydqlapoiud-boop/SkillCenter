package com.huawei.skillcenter.quality;

public record EvaluationCase(String id, String name) {
    public EvaluationCase {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException("evaluation case id must be 1-64 safe identifier characters");
        }
        if (name == null || name.isBlank() || name.length() > 160
                || EvaluationSuiteValidator.containsControlCharacter(name)) {
            throw new IllegalArgumentException(
                    "evaluation case name must be 1-160 characters without control characters");
        }
    }
}
