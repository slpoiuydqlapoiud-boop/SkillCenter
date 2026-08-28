package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompatibilityMatrixDomainTest {
    @Test
    void builderDeduplicatesAndSortsCartesianCombinations() {
        List<CompatibilityMatrixCombination> combinations = CompatibilityMatrixCombinationBuilder.build(
                List.of("runtime-b", "runtime-a", "runtime-a"),
                List.of("mcp-a", "mcp-b"),
                List.of("llm-a"));

        assertThat(combinations).containsExactly(
                new CompatibilityMatrixCombination("runtime-a", "mcp-a", "llm-a"),
                new CompatibilityMatrixCombination("runtime-a", "mcp-b", "llm-a"),
                new CompatibilityMatrixCombination("runtime-b", "mcp-a", "llm-a"),
                new CompatibilityMatrixCombination("runtime-b", "mcp-b", "llm-a"));
    }

    @Test
    void builderUsesAnEmptyValueForAnUnselectedDimension() {
        assertThat(CompatibilityMatrixCombinationBuilder.build(
                List.of("runtime-a"), List.of(), List.of()))
                .containsExactly(new CompatibilityMatrixCombination("runtime-a", "", ""));
    }

    @Test
    void builderRejectsMoreThanOneHundredCombinations() {
        assertThatThrownBy(() -> CompatibilityMatrixCombinationBuilder.build(
                identifiers("runtime", 10), identifiers("mcp", 10), identifiers("llm", 2)))
                .hasMessageContaining("100");
    }

    @Test
    void requestRejectsInvalidPolicyAndNormalizesDefaults() {
        CompatibilityMatrixCreateRequest request = new CompatibilityMatrixCreateRequest(
                "eox-query", "1.3.0", "smoke", List.of("openclaw"), List.of("mcp-network"),
                List.of("llm-gateway"), "ALL_MUST_PASS", 1, true, "success", 1_000);

        assertThat(request.dataSource()).isEqualTo("mock");
        assertThat(request.policy()).isEqualTo(CompatibilityMatrixPolicy.ALL_MUST_PASS);

        assertThatThrownBy(() -> new CompatibilityMatrixCreateRequest(
                "eox-query", "1.3.0", "smoke", List.of("openclaw"), List.of(), List.of(),
                "MIN_PASS_RATE", 1.1, false, "success", 1_000))
                .hasMessageContaining("minimumPassRate");

        CompatibilityMatrixCreateRequest versioned = new CompatibilityMatrixCreateRequest(
                "eox-query", "1.3.0", "smoke", "smoke-v1", List.of("openclaw"), List.of(), List.of(),
                "ALL_MUST_PASS", 1, false, "success", 1_000);
        assertThat(versioned.suiteVersion()).isEqualTo("smoke-v1");
    }

    @Test
    void terminalRunRequiresCompletionTimeAndCasesKeepBoundedIdentifiers() {
        assertThatThrownBy(() -> new CompatibilityMatrixRun(
                "00000000-0000-0000-0000-000000000001", "eox-query", "1.3.0", "smoke", "smoke-v1",
                CompatibilityMatrixPolicy.ALL_MUST_PASS, 1, false,
                CompatibilityMatrixStatus.COMPLETED, "mock", "success", 1_000, 1, 1, 1, 100, 1,
                QualityGateStatus.PASSED, List.of(), "admin", java.time.Instant.EPOCH, null))
                .hasMessageContaining("completedAt");
    }

    @Test
    void caseKeepsItsParentMatrixIdentity() {
        CompatibilityMatrixCase matrixCase = new CompatibilityMatrixCase(
                "case-1", "matrix-1", "evaluation-1", "runtime-a", "", "",
                "runtime-v1", "", "", com.huawei.skillcenter.execution.ExecutionEnvironmentStatus.ACTIVE,
                null, null, CompatibilityMatrixCaseStatus.QUEUED, 0, QualityGateStatus.BLOCKED,
                List.of("EVALUATION_NOT_COMPLETED"), "", null, null);

        assertThat(matrixCase.matrixRunId()).isEqualTo("matrix-1");
    }

    @Test
    void caseKeepsSafeEnvironmentCapabilityAndProviderSnapshots() {
        CompatibilityMatrixEnvironmentSnapshot runtime = new CompatibilityMatrixEnvironmentSnapshot(
                "runtime-a", "runtime-v1", com.huawei.skillcenter.execution.ExecutionEnvironmentStatus.ACTIVE,
                List.of("execute", "timeout"), "openclaw-runner");
        CompatibilityMatrixCase matrixCase = new CompatibilityMatrixCase(
                "case-1", "matrix-1", "", "runtime-a", "", "", "runtime-v1", "", "",
                com.huawei.skillcenter.execution.ExecutionEnvironmentStatus.ACTIVE, null, null,
                CompatibilityMatrixCaseStatus.QUEUED, 0, QualityGateStatus.BLOCKED,
                List.of("EVALUATION_NOT_COMPLETED"), "", null, null, runtime, null, null);

        assertThat(matrixCase.runtimeEnvironment().capabilities()).containsExactly("execute", "timeout");
        assertThat(matrixCase.runtimeEnvironment().adapterProviderId()).isEqualTo("openclaw-runner");
    }

    private static List<String> identifiers(String prefix, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> prefix + "-" + index)
                .toList();
    }
}
