package com.huawei.skillcenter.quality;

import java.util.List;

public record QualityEvidenceState(
        List<EvaluationSuite> suites,
        QualityRuleSet rules,
        List<EvaluationRun> runs,
        List<QualitySnapshot> snapshots,
        List<EvaluationCaseResult> caseResults,
        List<CompatibilityMatrixRun> matrixRuns,
        List<CompatibilityMatrixCase> matrixCases
) {
    public QualityEvidenceState(List<EvaluationSuite> suites, QualityRuleSet rules,
                                List<EvaluationRun> runs, List<QualitySnapshot> snapshots) {
        this(suites, rules, runs, snapshots, List.of());
    }

    public QualityEvidenceState(List<EvaluationSuite> suites, QualityRuleSet rules,
                                List<EvaluationRun> runs, List<QualitySnapshot> snapshots,
                                List<EvaluationCaseResult> caseResults) {
        this(suites, rules, runs, snapshots, caseResults, List.of(), List.of());
    }

    public QualityEvidenceState {
        suites = List.copyOf(suites == null ? List.of() : suites);
        runs = List.copyOf(runs == null ? List.of() : runs);
        snapshots = List.copyOf(snapshots == null ? List.of() : snapshots);
        caseResults = List.copyOf(caseResults == null ? List.of() : caseResults);
        matrixRuns = List.copyOf(matrixRuns == null ? List.of() : matrixRuns);
        matrixCases = List.copyOf(matrixCases == null ? List.of() : matrixCases);
    }
}
