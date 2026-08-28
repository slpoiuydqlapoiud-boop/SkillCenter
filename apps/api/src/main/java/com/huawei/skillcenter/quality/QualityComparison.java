package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironmentSnapshot;
import java.time.Instant;

public record QualityComparison(
        String skillId,
        String baselineVersion,
        String candidateVersion,
        boolean comparable,
        String reasonCode,
        String reason,
        Metric baseline,
        Metric candidate,
        Delta delta
) {
    public record Metric(
            int score,
            int staticScore,
            double passRate,
            double successRate,
            long p95Ms,
            long totalCalls,
            String gateStatus,
            String dataSource,
            String suiteVersion,
            String ruleVersion,
            String runnerId,
            String evaluationProviderId,
            Instant measuredAt,
            String runtimeId,
            String mcpServerId,
            String llmProviderId,
            ExecutionEnvironmentSnapshot runtimeEnvironment,
            ExecutionEnvironmentSnapshot mcpServerEnvironment,
            ExecutionEnvironmentSnapshot llmProviderEnvironment
    ) {
        public Metric(int score, int staticScore, double passRate, double successRate, long p95Ms,
                      long totalCalls, String gateStatus, String dataSource, String suiteVersion,
                      String ruleVersion, String runnerId, String evaluationProviderId, Instant measuredAt) {
            this(score, staticScore, passRate, successRate, p95Ms, totalCalls, gateStatus, dataSource,
                    suiteVersion, ruleVersion, runnerId, evaluationProviderId, measuredAt, "", "", "");
        }

        public Metric(int score, int staticScore, double passRate, double successRate, long p95Ms,
                      long totalCalls, String gateStatus, String dataSource, String suiteVersion,
                      String ruleVersion, String runnerId, String evaluationProviderId, Instant measuredAt,
                      String runtimeId, String mcpServerId, String llmProviderId) {
            this(score, staticScore, passRate, successRate, p95Ms, totalCalls, gateStatus, dataSource,
                    suiteVersion, ruleVersion, runnerId, evaluationProviderId, measuredAt, runtimeId,
                    mcpServerId, llmProviderId, null, null, null);
        }
    }

    public record Delta(
            int score,
            int staticScore,
            double passRate,
            double successRate,
            long p95Ms,
            long totalCalls
    ) {
    }
}
