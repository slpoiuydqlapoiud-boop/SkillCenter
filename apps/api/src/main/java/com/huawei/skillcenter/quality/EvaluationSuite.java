package com.huawei.skillcenter.quality;

import java.util.List;

public record EvaluationSuite(String id, String name, String version, boolean enabled, List<EvaluationCase> cases) {
    public EvaluationSuite(String id, String version, List<EvaluationCase> cases) {
        this(id, id, version, true, cases);
    }

    public EvaluationSuite {
        EvaluationSuiteValidator.validateId(id);
        EvaluationSuiteValidator.validateName(name);
        EvaluationSuiteValidator.validateVersion(version);
        EvaluationSuiteValidator.validateCases(cases);
        cases = List.copyOf(cases);
    }
}
