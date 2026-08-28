package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.RuntimeOperationsQuery;
import com.huawei.skillcenter.operations.RuntimeOperationsService;
import com.huawei.skillcenter.operations.RuntimeOperationsWindow;
import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class QualityComparisonService {
    private final QualityEvaluationService evaluationService;
    private final RuntimeOperationsService runtimeOperationsService;
    private final QualityComparisonCalculator calculator;

    @Autowired
    public QualityComparisonService(QualityEvaluationService evaluationService,
                                    RuntimeOperationsService runtimeOperationsService) {
        this(evaluationService, runtimeOperationsService, new QualityComparisonCalculator());
    }

    QualityComparisonService(QualityEvaluationService evaluationService,
                             RuntimeOperationsService runtimeOperationsService,
                             QualityComparisonCalculator calculator) {
        this.evaluationService = evaluationService;
        this.runtimeOperationsService = runtimeOperationsService;
        this.calculator = calculator;
    }

    public SkillQualityDetail detail(String skillId, String version, RuntimeOperationsWindow window,
                                     String dataSource) {
        return detail(skillId, version, window, dataSource, null, null, null);
    }

    public SkillQualityDetail detail(String skillId, String version, RuntimeOperationsWindow window,
                                     String dataSource, String runtimeId, String mcpServerId,
                                     String llmProviderId) {
        List<QualitySnapshot> snapshots = evaluationService.snapshots(skillId, dataSource, runtimeId, mcpServerId, llmProviderId);
        QualitySnapshot latest = snapshots.stream()
                .filter(snapshot -> version == null || version.isBlank() || version.equals(snapshot.skillVersion()))
                .findFirst().orElse(null);
        String effectiveVersion = version == null || version.isBlank()
                ? latest == null ? null : latest.skillVersion() : version;
        RuntimeOperationsSnapshot runtime = runtimeOperationsService.snapshot(
                new RuntimeOperationsQuery(window, skillId, effectiveVersion, null, dataSource, null,
                        runtimeId, mcpServerId, llmProviderId));
        List<String> versions = snapshots.stream().map(QualitySnapshot::skillVersion).distinct()
                .sorted(Comparator.reverseOrder()).toList();
        return new SkillQualityDetail(skillId, effectiveVersion, latest, runtime, versions, snapshots);
    }

    public QualityComparison compare(String skillId, String baselineVersion, String candidateVersion,
                                     RuntimeOperationsWindow window, String dataSource) {
        return compare(skillId, baselineVersion, candidateVersion, window, dataSource, null, null, null);
    }

    public QualityComparison compare(String skillId, String baselineVersion, String candidateVersion,
                                     RuntimeOperationsWindow window, String dataSource, String runtimeId,
                                     String mcpServerId, String llmProviderId) {
        return compare(skillId, baselineVersion, candidateVersion, window, dataSource, runtimeId, mcpServerId,
                llmProviderId, null, null);
    }

    public QualityComparison compare(String skillId, String baselineVersion, String candidateVersion,
                                     RuntimeOperationsWindow window, String dataSource, String runtimeId,
                                     String mcpServerId, String llmProviderId,
                                     String suiteId, String suiteVersion) {
        QualitySnapshot baseline = latest(skillId, baselineVersion, dataSource, runtimeId, mcpServerId,
                llmProviderId, suiteId, suiteVersion);
        QualitySnapshot candidate = latest(skillId, candidateVersion, dataSource, runtimeId, mcpServerId,
                llmProviderId, suiteId, suiteVersion);
        RuntimeOperationsSnapshot baselineRuntime = runtimeOperationsService.snapshot(
                new RuntimeOperationsQuery(window, skillId, baselineVersion, null, dataSource, null,
                        runtimeId, mcpServerId, llmProviderId));
        RuntimeOperationsSnapshot candidateRuntime = runtimeOperationsService.snapshot(
                new RuntimeOperationsQuery(window, skillId, candidateVersion, null, dataSource, null,
                        runtimeId, mcpServerId, llmProviderId));
        return calculator.compare(skillId, baselineVersion, candidateVersion, baseline, candidate,
                baselineRuntime, candidateRuntime);
    }

    private QualitySnapshot latest(String skillId, String version, String dataSource, String runtimeId, String mcpServerId,
                                   String llmProviderId, String suiteId, String suiteVersion) {
        return evaluationService.snapshots(skillId, dataSource, runtimeId, mcpServerId, llmProviderId).stream()
                .filter(snapshot -> version.equals(snapshot.skillVersion()))
                .filter(snapshot -> suiteId == null || suiteId.isBlank() || suiteId.equals(snapshot.suiteId()))
                .filter(snapshot -> suiteVersion == null || suiteVersion.isBlank()
                        || suiteVersion.equals(snapshot.suiteVersion()))
                .findFirst().orElse(null);
    }
}
