package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironmentSnapshot;
import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;

public class QualityComparisonCalculator {
    public QualityComparison compare(String skillId, String baselineVersion, String candidateVersion,
                                     QualitySnapshot baseline, QualitySnapshot candidate,
                                     RuntimeOperationsSnapshot baselineRuntime,
                                     RuntimeOperationsSnapshot candidateRuntime) {
        if (baseline == null || candidate == null) {
            return new QualityComparison(skillId, baselineVersion, candidateVersion, false,
                    "NO_COMPARABLE_SNAPSHOT", "两个版本都需要有可用的质量快照", metric(baseline, baselineRuntime),
                    metric(candidate, candidateRuntime), null);
        }
        if (!sameContext(baseline, candidate) || !sameRuntimeContext(baselineRuntime, candidateRuntime)) {
            return new QualityComparison(skillId, baselineVersion, candidateVersion, false,
                    "EVALUATION_CONTEXT_MISMATCH", "评测套件、规则、Runner、评测 Provider 或数据来源不一致",
                    metric(baseline, baselineRuntime), metric(candidate, candidateRuntime), null);
        }
        QualityComparison.Metric left = metric(baseline, baselineRuntime);
        QualityComparison.Metric right = metric(candidate, candidateRuntime);
        return new QualityComparison(skillId, baselineVersion, candidateVersion, true, "COMPARABLE",
                "两个版本使用相同评测口径，可直接比较", left, right,
                new QualityComparison.Delta(right.score() - left.score(), right.staticScore() - left.staticScore(),
                        round(right.passRate() - left.passRate()), round(right.successRate() - left.successRate()),
                        right.p95Ms() - left.p95Ms(), right.totalCalls() - left.totalCalls()));
    }

    private boolean sameContext(QualitySnapshot left, QualitySnapshot right) {
        return equals(left.suiteId(), right.suiteId())
                && equals(left.suiteVersion(), right.suiteVersion())
                && equals(left.ruleVersion(), right.ruleVersion())
                && equals(left.runnerId(), right.runnerId())
                && equals(left.evaluationProviderId(), right.evaluationProviderId())
                && equals(left.dataSource(), right.dataSource())
                && equals(left.runtimeId(), right.runtimeId())
                && equals(left.mcpServerId(), right.mcpServerId())
                && equals(left.llmProviderId(), right.llmProviderId())
                && sameEnvironmentSnapshot(left.runtimeEnvironment(), right.runtimeEnvironment())
                && sameEnvironmentSnapshot(left.mcpServerEnvironment(), right.mcpServerEnvironment())
                && sameEnvironmentSnapshot(left.llmProviderEnvironment(), right.llmProviderEnvironment());
    }

    private boolean sameEnvironmentSnapshot(ExecutionEnvironmentSnapshot left,
                                             ExecutionEnvironmentSnapshot right) {
        if (left == null || right == null || !left.complete() || !right.complete()) {
            return true;
        }
        return left.equals(right);
    }

    private boolean sameRuntimeContext(com.huawei.skillcenter.operations.RuntimeOperationsSnapshot left,
                                       com.huawei.skillcenter.operations.RuntimeOperationsSnapshot right) {
        if (left == null || right == null) {
            return true;
        }
        return java.util.Objects.equals(left.window(), right.window())
                && java.util.Objects.equals(left.dataSource(), right.dataSource());
    }

    private QualityComparison.Metric metric(QualitySnapshot snapshot, RuntimeOperationsSnapshot runtime) {
        RuntimeOperationsSnapshot.Totals totals = runtime == null
                ? RuntimeOperationsSnapshot.Totals.empty() : runtime.totals();
        RuntimeOperationsSnapshot.Latency latency = runtime == null
                ? RuntimeOperationsSnapshot.Latency.empty() : runtime.latency();
        if (snapshot == null) {
            return null;
        }
        return new QualityComparison.Metric(snapshot.score(), snapshot.staticScore(), snapshot.passRate(),
                totals.successRate(), latency.p95Ms(), totals.total(), snapshot.gateStatus().name(),
                snapshot.dataSource(), snapshot.suiteVersion(), snapshot.ruleVersion(), snapshot.runnerId(),
                snapshot.evaluationProviderId(), snapshot.measuredAt(), snapshot.runtimeId(), snapshot.mcpServerId(),
                snapshot.llmProviderId(), snapshot.runtimeEnvironment(), snapshot.mcpServerEnvironment(),
                snapshot.llmProviderEnvironment());
    }

    private boolean equals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
