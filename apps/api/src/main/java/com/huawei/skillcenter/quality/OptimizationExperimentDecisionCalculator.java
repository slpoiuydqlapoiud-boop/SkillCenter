package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/** Pure, deterministic decision rules for an already completed experiment. */
public class OptimizationExperimentDecisionCalculator {
    public OptimizationExperimentDecision calculate(OptimizationExperiment experiment,
                                                     QualitySnapshot snapshot,
                                                     BenchmarkResult benchmark,
                                                     String evaluatedBy,
                                                     Instant evaluatedAt) {
        Objects.requireNonNull(experiment, "experiment must not be null");
        Objects.requireNonNull(snapshot, "quality snapshot must not be null");
        Objects.requireNonNull(benchmark, "benchmark must not be null");
        String conclusion = normalize(benchmark.conclusion());
        String decision;
        String reasonCode;
        String reason;
        String recommendedAction;
        if (snapshot.gateStatus() == QualityGateStatus.BLOCKED) {
            decision = OptimizationExperimentDecision.REJECT_CANDIDATE;
            reasonCode = "QUALITY_GATE_BLOCKED";
            String reasons = snapshot.gateReasons().stream().sorted().collect(Collectors.joining(","));
            reason = reasons.isBlank() ? "质量门禁未通过" : "质量门禁未通过：" + reasons;
            recommendedAction = "修复质量门禁问题并重新评测";
        } else if ("IMPROVED".equals(conclusion)) {
            decision = OptimizationExperimentDecision.PROMOTE_CANDIDATE;
            reasonCode = "BENCHMARK_IMPROVED";
            reason = "候选版本在同口径 Benchmark 中表现改善";
            recommendedAction = "提交人工发布审核";
        } else if ("REGRESSED".equals(conclusion)) {
            decision = OptimizationExperimentDecision.REJECT_CANDIDATE;
            reasonCode = "BENCHMARK_REGRESSED";
            reason = "候选版本在同口径 Benchmark 中出现回归";
            recommendedAction = "保留基线并修复回归";
        } else if ("MIXED".equals(conclusion) || "NO_CHANGE".equals(conclusion)) {
            decision = OptimizationExperimentDecision.ITERATE;
            reasonCode = "BENCHMARK_NEEDS_ITERATION";
            reason = "候选版本未形成单一方向的整体改善";
            recommendedAction = "针对受影响指标继续优化";
        } else {
            decision = OptimizationExperimentDecision.NOT_COMPARABLE;
            reasonCode = "BENCHMARK_NOT_COMPARABLE";
            reason = "缺少同口径可比 Benchmark 证据";
            recommendedAction = "补齐同口径基线证据";
        }
        return new OptimizationExperimentDecision("decision-" + experiment.experimentId(), experiment.experimentId(),
                experiment.skillId(), experiment.sourceVersion(), experiment.candidateVersion(), experiment.dataSource(),
                experiment.runtimeId(), experiment.mcpServerId(), experiment.llmProviderId(), experiment.suiteId(),
                experiment.suiteVersion(), snapshot.snapshotId(), benchmark.benchmarkId(), snapshot.gateStatus(),
                conclusion, decision, reasonCode, reason, recommendedAction, evaluatedBy, evaluatedAt);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
