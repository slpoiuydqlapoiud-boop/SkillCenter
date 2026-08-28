package com.huawei.skillcenter.quality;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class EvaluationSuiteValidator {
    private static final int MAX_CASES = 100;

    private EvaluationSuiteValidator() {
    }

    static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException("suite id must be 1-64 safe identifier characters");
        }
    }

    static void validateVersion(String version) {
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("suite version must be 1-64 safe identifier characters");
        }
    }

    static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 120 || containsControlCharacter(name)) {
            throw new IllegalArgumentException("suite name must be 1-120 characters without control characters");
        }
    }

    static void validateCases(List<EvaluationCase> cases) {
        if (cases == null || cases.isEmpty() || cases.size() > MAX_CASES) {
            throw new IllegalArgumentException("suite cases must contain 1-100 items");
        }
        Set<String> ids = new HashSet<>();
        for (EvaluationCase evaluationCase : cases) {
            if (evaluationCase == null) {
                throw new IllegalArgumentException("evaluation case must not be null");
            }
            if (!ids.add(evaluationCase.id())) {
                throw new IllegalArgumentException("duplicate evaluation case id: " + evaluationCase.id());
            }
        }
    }

    static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
