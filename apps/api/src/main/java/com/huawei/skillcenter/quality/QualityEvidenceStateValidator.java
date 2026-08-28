package com.huawei.skillcenter.quality;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Shared semantic validation for persisted quality evidence aggregates. */
final class QualityEvidenceStateValidator {
    private QualityEvidenceStateValidator() { }

    static void validate(QualityEvidenceState state) {
        if (state == null) throw new IllegalArgumentException("quality evidence state is required");
        Set<String> runIds = new HashSet<>(), snapshotIds = new HashSet<>(), caseResultIds = new HashSet<>(), suiteKeys = new HashSet<>();
        Map<String, EvaluationSuite> suites = new HashMap<>(); Map<String, Integer> enabled = new HashMap<>();
        for (EvaluationSuite suite : state.suites()) {
            if (suite == null || blank(suite.id()) || blank(suite.version()) || suite.cases() == null) fail("persisted evaluation suite is invalid");
            String key = suite.id() + "\u0000" + suite.version(); if (!suiteKeys.add(key)) fail("persisted evaluation suite version is duplicated");
            suites.put(key, suite); if (suite.enabled()) enabled.merge(suite.id(), 1, Integer::sum);
            Set<String> caseIds = new HashSet<>();
            for (EvaluationCase item : suite.cases()) { if (item == null || blank(item.id())) fail("persisted evaluation case is invalid"); if (!caseIds.add(item.id())) fail("persisted evaluation case id is duplicated"); }
        }
        enabled.forEach((id, count) -> { if (count > 1) fail("persisted evaluation suite has multiple enabled versions: " + id); });
        Map<String, EvaluationRun> runs = new HashMap<>(); Set<String> experimentIds = new HashSet<>();
        for (EvaluationRun run : state.runs()) {
            if (run == null || blank(run.id()) || blank(run.skillId()) || blank(run.skillVersion()) || blank(run.suiteId()) || blank(run.suiteVersion()) || run.status() == null || blank(run.providerId()) || blank(run.evaluationProviderId()) || blank(run.dataSource()) || run.createdAt() == null || run.gateStatus() == null || run.totalCases() < 0 || run.passedCases() < 0 || run.passedCases() > run.totalCases() || run.score() < 0 || run.score() > 100 || (terminal(run.status()) && run.completedAt() == null) || (run.completedAt() != null && run.completedAt().isBefore(run.createdAt()))) fail("persisted evaluation run is invalid");
            if (!suites.isEmpty() && !suites.containsKey(run.suiteId() + "\u0000" + run.suiteVersion())) fail("persisted evaluation run references an unknown suite version");
            if (!runIds.add(run.id())) fail("persisted evaluation run id is duplicated");
            if (!blank(run.experimentId()) && !experimentIds.add(run.experimentId())) fail("persisted evaluation run experimentId is duplicated"); runs.put(run.id(), run);
        }
        for (QualitySnapshot snapshot : state.snapshots()) {
            if (snapshot == null || blank(snapshot.snapshotId()) || blank(snapshot.skillId()) || blank(snapshot.skillVersion()) || blank(snapshot.suiteId()) || blank(snapshot.suiteVersion()) || blank(snapshot.runnerId()) || blank(snapshot.evaluationProviderId()) || blank(snapshot.dataSource()) || snapshot.measuredAt() == null || snapshot.score() < 0 || snapshot.score() > 100 || snapshot.totalCases() < 0 || snapshot.passedCases() < 0 || snapshot.passedCases() > snapshot.totalCases() || snapshot.gateStatus() == null) fail("persisted quality snapshot is invalid");
            EvaluationRun run = runs.get(snapshot.snapshotId()); if (run == null) fail("persisted quality snapshot references an unknown run");
            if (!run.skillId().equals(snapshot.skillId()) || !run.skillVersion().equals(snapshot.skillVersion())) fail("persisted quality snapshot skill/version does not match its run");
            if (!run.suiteId().equals(snapshot.suiteId()) || !run.suiteVersion().equals(snapshot.suiteVersion())) fail("persisted quality snapshot suite does not match its run");
            if (!run.providerId().equals(snapshot.runnerId()) || !run.evaluationProviderId().equals(snapshot.evaluationProviderId()) || !run.dataSource().equals(snapshot.dataSource())) fail("persisted quality snapshot providers/data source do not match its run");
            if (!same(run.runtimeId(), snapshot.runtimeId()) || !same(run.mcpServerId(), snapshot.mcpServerId()) || !same(run.llmProviderId(), snapshot.llmProviderId())) fail("persisted quality snapshot execution environment does not match its run");
            if (run.status() != EvaluationRunStatus.COMPLETED) fail("persisted quality snapshot references a non-completed run"); if (!snapshotIds.add(snapshot.snapshotId())) fail("persisted quality snapshot id is duplicated");
        }
        for (EvaluationCaseResult result : state.caseResults()) {
            if (result == null || result.evaluatedAt() == null) fail("persisted evaluation case result is invalid"); EvaluationRun run = runs.get(result.runId()); if (run == null) fail("persisted evaluation case result references an unknown run");
            EvaluationSuite suite = suites.get(run.suiteId() + "\u0000" + run.suiteVersion()); if (suite != null && suite.cases().stream().noneMatch(item -> result.caseId().equals(item.id()))) fail("persisted evaluation case result references an unknown case");
            if (!caseResultIds.add(result.runId() + "\u0000" + result.caseId())) fail("persisted evaluation case result is duplicated");
        }
        validateMatrices(state, runs);
    }

    private static void validateMatrices(QualityEvidenceState state, Map<String, EvaluationRun> evaluations) {
        Set<String> ids = new HashSet<>(); Map<String, CompatibilityMatrixRun> matrices = new HashMap<>(); Map<String, Integer> active = new HashMap<>();
        for (CompatibilityMatrixRun matrix : state.matrixRuns()) { if (matrix == null || !ids.add(matrix.matrixRunId())) fail("persisted compatibility matrix id is duplicated"); matrices.put(matrix.matrixRunId(), matrix); if (matrix.releaseGateRequired() && (matrix.status() == CompatibilityMatrixStatus.QUEUED || matrix.status() == CompatibilityMatrixStatus.RUNNING)) active.merge(matrix.skillId()+"\u0000"+matrix.skillVersion(), 1, Integer::sum); }
        active.forEach((key, count) -> { if (count > 1) fail("multiple active release compatibility matrices exist"); });
        Set<String> caseIds = new HashSet<>(), combinations = new HashSet<>(); Map<String,Integer> counts = new HashMap<>(), completed = new HashMap<>(), passed = new HashMap<>(); Map<String,Boolean> nonTerminal = new HashMap<>();
        for (CompatibilityMatrixCase item : state.matrixCases()) {
            if (item == null || !caseIds.add(item.caseId())) fail("persisted compatibility matrix case id is duplicated"); CompatibilityMatrixRun matrix = matrices.get(item.matrixRunId()); if (matrix == null) fail("persisted compatibility matrix case references an unknown matrix");
            if (!combinations.add(item.matrixRunId()+"\u0000"+item.runtimeId()+"\u0000"+item.mcpServerId()+"\u0000"+item.llmProviderId())) fail("persisted compatibility matrix combination is duplicated"); counts.merge(item.matrixRunId(),1,Integer::sum); if (item.status().terminal()) completed.merge(item.matrixRunId(),1,Integer::sum); else nonTerminal.put(item.matrixRunId(),true); if (item.status()==CompatibilityMatrixCaseStatus.COMPLETED && item.gateStatus()==QualityGateStatus.PASSED) passed.merge(item.matrixRunId(),1,Integer::sum);
            if (!item.evaluationRunId().isBlank()) { EvaluationRun evaluation=evaluations.get(item.evaluationRunId()); if (evaluation==null) fail("persisted compatibility matrix case references an unknown evaluation"); if (!matrix.skillId().equals(evaluation.skillId()) || !matrix.skillVersion().equals(evaluation.skillVersion()) || !matrix.suiteId().equals(evaluation.suiteId()) || !matrix.suiteVersion().equals(evaluation.suiteVersion()) || !matrix.dataSource().equals(evaluation.dataSource()) || !same(item.runtimeId(),evaluation.runtimeId()) || !same(item.mcpServerId(),evaluation.mcpServerId()) || !same(item.llmProviderId(),evaluation.llmProviderId())) fail("persisted compatibility matrix case evaluation context does not match"); }
            else if (item.status().terminal() && (item.errorCode()==null || item.errorCode().isBlank())) fail("terminal compatibility matrix case without an evaluation requires an error code");
        }
        for (CompatibilityMatrixRun matrix : state.matrixRuns()) { String id=matrix.matrixRunId(); if (counts.getOrDefault(id,0)!=matrix.totalCases()) fail("compatibility matrix case count does not match its run"); if (completed.getOrDefault(id,0)!=matrix.completedCases() || passed.getOrDefault(id,0)!=matrix.passedCases()) fail("compatibility matrix aggregate counters do not match its cases"); if (matrix.status().terminal() && nonTerminal.getOrDefault(id,false)) fail("terminal compatibility matrix has non-terminal cases"); }
    }
    private static boolean terminal(EvaluationRunStatus status) { return status == EvaluationRunStatus.COMPLETED || status == EvaluationRunStatus.FAILED || status == EvaluationRunStatus.TIMED_OUT || status == EvaluationRunStatus.CANCELLED; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean same(String left, String right) { return java.util.Objects.equals(left == null ? "" : left, right == null ? "" : right); }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
