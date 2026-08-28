package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironmentKind;
import com.huawei.skillcenter.execution.ExecutionEnvironmentService;
import com.huawei.skillcenter.execution.ExecutionEnvironmentSnapshot;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.operations.RuntimeSummary;
import com.huawei.skillcenter.operations.RuntimeSummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class SkillExecutionService {
    private final SkillRunner runner;
    private final GovernanceStore governanceStore;
    private final RuntimeSummaryService runtimeSummaryService;
    private final SkillExecutionStore executionStore;
    private final ExecutionEnvironmentService executionEnvironmentService;
    private final Clock clock;

    @Autowired
    public SkillExecutionService(SkillRunner runner, GovernanceStore governanceStore,
                                 RuntimeSummaryService runtimeSummaryService, SkillExecutionStore executionStore,
                                 ExecutionEnvironmentService executionEnvironmentService) {
        this(runner, governanceStore, runtimeSummaryService, executionStore, Clock.systemUTC(), executionEnvironmentService);
    }

    SkillExecutionService(SkillRunner runner, GovernanceStore governanceStore,
                          RuntimeSummaryService runtimeSummaryService, SkillExecutionStore executionStore,
                          Clock clock) {
        this(runner, governanceStore, runtimeSummaryService, executionStore, clock, null);
    }

    SkillExecutionService(SkillRunner runner, GovernanceStore governanceStore,
                          RuntimeSummaryService runtimeSummaryService, SkillExecutionStore executionStore,
                          Clock clock, ExecutionEnvironmentService executionEnvironmentService) {
        this.runner = runner;
        this.governanceStore = governanceStore;
        this.runtimeSummaryService = runtimeSummaryService;
        this.executionStore = executionStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.executionEnvironmentService = executionEnvironmentService;
    }

    public SkillExecutionRecord execute(SkillExecutionRequest request, Actor actor, String requestId) {
        RoleGuard.require(actor, java.util.Set.of("admin"));
        EnvironmentSnapshots environments = validateExecutionEnvironments(request);
        ensurePublished(request);
        UUID executionId = UUID.randomUUID();
        RunnerExecutionResult result = runner.execute(new RunnerExecutionRequest(
                request.skillId(), request.skillVersion(), executionId.toString(), "runtime", "direct",
                request.timeoutMs(), request.scenario(), request.runtimeId(), request.mcpServerId(),
                request.llmProviderId()));
        var executedAt = clock.instant();
        SkillExecutionRecord record = new SkillExecutionRecord(executionId, request.skillId(), request.skillVersion(),
                result.status(), result.providerId(), result.providerVersion(), result.dataSource(), result.durationMs(),
                result.outputHash(), result.errorCode(), executedAt, request.runtimeId(), request.mcpServerId(),
                request.llmProviderId(), environments.runtime(), environments.mcpServer(), environments.llmProvider());
        executionStore.save(record);
        runtimeSummaryService.ingest(new RuntimeSummary(
                "1.0", executionId, OffsetDateTime.ofInstant(executedAt, ZoneOffset.UTC), request.skillId(),
                request.skillVersion(), runtimeStatus(result.status()), result.durationMs(), runtimeError(result),
                result.dataSource(), null, "runner-api", executionId.toString(), request.runtimeId(),
                request.mcpServerId(), request.llmProviderId()));
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(), "RUNNER_EXECUTED", "SKILL_VERSION",
                request.skillId() + ":" + request.skillVersion(), actor.userId(), actor.role(), requestId, executedAt,
                java.util.Map.of("executionId", executionId.toString(), "status", result.status().name(),
                        "dataSource", result.dataSource(), "providerId", result.providerId(),
                        "runtimeId", request.runtimeId(), "mcpServerId", request.mcpServerId(),
                        "llmProviderId", request.llmProviderId())));
        return record;
    }

    private EnvironmentSnapshots validateExecutionEnvironments(SkillExecutionRequest request) {
        if (executionEnvironmentService == null) {
            return new EnvironmentSnapshots(
                    snapshot(ExecutionEnvironmentKind.AGENT_RUNTIME, request.runtimeId()),
                    snapshot(ExecutionEnvironmentKind.MCP_SERVER, request.mcpServerId()),
                    snapshot(ExecutionEnvironmentKind.LLM_PROVIDER, request.llmProviderId()));
        }
        return new EnvironmentSnapshots(
                executionEnvironmentService.requireActiveSnapshot(ExecutionEnvironmentKind.AGENT_RUNTIME, request.runtimeId()),
                executionEnvironmentService.requireActiveSnapshot(ExecutionEnvironmentKind.MCP_SERVER, request.mcpServerId()),
                executionEnvironmentService.requireActiveSnapshot(ExecutionEnvironmentKind.LLM_PROVIDER, request.llmProviderId()));
    }

    private ExecutionEnvironmentSnapshot snapshot(ExecutionEnvironmentKind kind, String environmentId) {
        return environmentId == null || environmentId.isBlank()
                ? ExecutionEnvironmentSnapshot.empty(kind)
                : ExecutionEnvironmentSnapshot.legacy(kind, environmentId);
    }

    private record EnvironmentSnapshots(ExecutionEnvironmentSnapshot runtime,
                                        ExecutionEnvironmentSnapshot mcpServer,
                                        ExecutionEnvironmentSnapshot llmProvider) {
    }

    public SkillExecutionRecord find(UUID executionId) {
        return executionStore.find(executionId).orElseThrow(() -> new SkillExecutionNotFoundException(executionId));
    }

    public List<SkillExecutionRecord> list(String skillId) {
        return list(skillId, null, null, null, null);
    }

    public List<SkillExecutionRecord> list(String skillId, String dataSource,
                                            String runtimeId, String mcpServerId, String llmProviderId) {
        return executionStore.findAll(skillId, normalizeDataSource(dataSource), normalizeEnvironment(runtimeId, "runtimeId"),
                normalizeEnvironment(mcpServerId, "mcpServerId"), normalizeEnvironment(llmProviderId, "llmProviderId"));
    }

    private String normalizeDataSource(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (normalized.isBlank() || "all".equals(normalized)) return null;
        if (!java.util.Set.of("mock", "production").contains(normalized)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
        return normalized;
    }

    private String normalizeEnvironment(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private void ensurePublished(SkillExecutionRequest request) {
        boolean published = governanceStore.snapshot().versions().stream()
                .anyMatch(version -> request.skillId().equals(version.skillId())
                        && request.skillVersion().equals(version.version())
                        && "published".equals(version.status()));
        if (!published) {
            throw new RunnerVersionNotAllowedException(request.skillId(), request.skillVersion());
        }
    }

    private String runtimeStatus(RunnerExecutionStatus status) {
        return switch (status) {
            case SUCCEEDED -> "success";
            case FAILED -> "failure";
            case TIMED_OUT -> "timeout";
            case CANCELLED -> "cancelled";
        };
    }

    private String runtimeError(RunnerExecutionResult result) {
        return switch (result.status()) {
            case FAILED, TIMED_OUT -> result.errorCode();
            case SUCCEEDED, CANCELLED -> null;
        };
    }
}
