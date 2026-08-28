package com.huawei.skillcenter.quality;

import java.util.List;

public record EvaluationSuiteRequest(
        String id,
        String name,
        String version,
        boolean enabled,
        List<EvaluationCase> cases
) {
    public EvaluationSuiteRequest {
        EvaluationSuiteValidator.validateId(id);
        EvaluationSuiteValidator.validateName(name);
        EvaluationSuiteValidator.validateVersion(version);
        EvaluationSuiteValidator.validateCases(cases);
        cases = List.copyOf(cases);
    }
}
