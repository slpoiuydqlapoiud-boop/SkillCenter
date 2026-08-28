package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;

import java.util.ArrayList;
import java.util.List;

/** Deterministic, read-only optimization signals; it never changes a Skill or starts execution. */
public class OptimizationSuggestionCalculator {
    static final double MIN_SUCCESS_RATE = 95.0;
    static final long MAX_P95_MS = 1_000;
    static final long MIN_RUNTIME_SAMPLES = 5;

    public List<OptimizationSuggestion> calculate(String skillId, String version,
                                                   QualitySnapshot quality,
                                                   RuntimeOperationsSnapshot runtime) {
        return calculate(skillId, version, quality, runtime, OptimizationSuggestionThresholds.defaults());
    }

    public List<OptimizationSuggestion> calculate(String skillId, String version,
                                                   QualitySnapshot quality,
                                                   RuntimeOperationsSnapshot runtime,
                                                   OptimizationSuggestionThresholds thresholds) {
        OptimizationSuggestionThresholds limits = thresholds == null
                ? OptimizationSuggestionThresholds.defaults() : thresholds;
        List<OptimizationSuggestion> suggestions = new ArrayList<>();
        if (quality == null) {
            suggestions.add(new OptimizationSuggestion("quality-data", skillId, version, "INFO",
                    "QUALITY_DATA", "补充质量评测", List.of("qualitySnapshot=missing"),
                    "在质量管理中心提交一次受控评测并保留套件/规则版本。"));
        } else if (quality.gateStatus() == QualityGateStatus.BLOCKED) {
            List<String> evidence = new ArrayList<>();
            evidence.add("gateStatus=BLOCKED");
            evidence.add("score=" + quality.score());
            evidence.addAll(quality.gateReasons());
            suggestions.add(new OptimizationSuggestion("quality-gate", skillId, version, "HIGH",
                    "QUALITY_GATE", "先修复质量门禁问题", evidence,
                    "根据门禁原因修复 Skill 或评测用例后重新评测，未通过前不要发布。"));
        }
        if (runtime == null || runtime.totals().total() < limits.minRuntimeSamples()) {
            long samples = runtime == null ? 0 : runtime.totals().total();
            suggestions.add(new OptimizationSuggestion("runtime-data", skillId, version, "INFO",
                    "RUNTIME_DATA", "补充运行样本", List.of("runtimeSamples=" + samples),
                    "继续采集脱敏运行摘要，达到最小样本后再判断稳定性和延迟趋势。"));
            return List.copyOf(suggestions);
        }
        double successRate = runtime.totals().successRate();
        if (successRate < limits.minSuccessRatePercent()) {
            List<String> evidence = new ArrayList<>();
            evidence.add("successRate=" + String.format(java.util.Locale.ROOT, "%.1f", successRate) + "%");
            if (!runtime.errors().isEmpty()) {
                evidence.add("error=" + runtime.errors().get(0).errorCode());
            }
            suggestions.add(new OptimizationSuggestion("runtime-reliability", skillId, version,
                    successRate < 90 ? "HIGH" : "MEDIUM", "RELIABILITY", "运行成功率偏低", evidence,
                    "优先分析错误码和失败版本，补充回归用例并验证重试/依赖超时边界。"));
        }
        if (runtime.latency().p95Ms() > limits.maxP95Ms()) {
            suggestions.add(new OptimizationSuggestion("runtime-latency", skillId, version, "MEDIUM",
                    "LATENCY", "运行 P95 延迟偏高", List.of("p95Ms=" + runtime.latency().p95Ms()),
                    "按版本和错误趋势拆分慢请求，检查外部依赖、上下文规模和超时配置。"));
        }
        return List.copyOf(suggestions);
    }
}
