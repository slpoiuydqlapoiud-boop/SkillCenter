package com.huawei.skillcenter.quality;

/** Classifies the observed effect without executing or mutating a Skill. */
public class BenchmarkEffectCalculator {
    public String classify(QualityComparison comparison) {
        if (comparison == null || !comparison.comparable() || comparison.delta() == null) {
            return "NOT_COMPARABLE";
        }
        QualityComparison.Delta delta = comparison.delta();
        boolean improved = delta.score() > 0 || delta.staticScore() > 0 || delta.passRate() > 0
                || delta.successRate() > 0 || delta.p95Ms() < 0;
        boolean regressed = delta.score() < 0 || delta.staticScore() < 0 || delta.passRate() < 0
                || delta.successRate() < 0 || delta.p95Ms() > 0;
        if (improved && regressed) return "MIXED";
        if (improved) return "IMPROVED";
        if (regressed) return "REGRESSED";
        return "NO_CHANGE";
    }
}
