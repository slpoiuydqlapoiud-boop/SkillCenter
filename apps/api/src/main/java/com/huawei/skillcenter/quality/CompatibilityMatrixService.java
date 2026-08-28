package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironment;
import com.huawei.skillcenter.execution.ExecutionEnvironmentKind;
import com.huawei.skillcenter.execution.ExecutionEnvironmentService;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CompatibilityMatrixService {
    private final QualityEvaluationService evaluationService;
    private final ExecutionEnvironmentService environmentService;
    private final QualityEvidenceRepository evidenceRepository;
    private final GovernanceStore governanceStore;
    private final Clock clock;
    private final Set<String> cancellationRequests = ConcurrentHashMap.newKeySet();
    private final Set<String> activeWorkers = ConcurrentHashMap.newKeySet();

    @Autowired
    public CompatibilityMatrixService(QualityEvaluationService evaluationService,
                                      ExecutionEnvironmentService environmentService,
                                      QualityEvidenceRepository evidenceRepository,
                                      GovernanceStore governanceStore) {
        this(evaluationService, environmentService, evidenceRepository, governanceStore, Clock.systemUTC());
    }

    public CompatibilityMatrixService(QualityEvaluationService evaluationService,
                                      ExecutionEnvironmentService environmentService,
                                      QualityEvidenceRepository evidenceRepository,
                                      GovernanceStore governanceStore, Clock clock) {
        this.evaluationService = evaluationService;
        this.environmentService = environmentService;
        this.evidenceRepository = evidenceRepository;
        this.governanceStore = governanceStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public CompatibilityMatrixRun create(CompatibilityMatrixCreateRequest request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null) throw new IllegalArgumentException("compatibility matrix request is required");
        EvaluationSuite suite = evaluationService.resolveSuite(request.suiteId(), request.suiteVersion());
        if (!suite.enabled()) {
            throw new EvaluationSuiteNotEnabledException("suite version is disabled: " + request.suiteId()
                    + "@" + suite.version());
        }
        List<CompatibilityMatrixCombination> combinations = CompatibilityMatrixCombinationBuilder.build(
                request.runtimeIds(), request.mcpServerIds(), request.llmProviderIds());
        Map<String, ExecutionEnvironment> environments = snapshotEnvironments(combinations);
        String matrixRunId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        CompatibilityMatrixRun run = new CompatibilityMatrixRun(matrixRunId, request.skillId(), request.skillVersion(),
                suite.id(), suite.version(), request.policy(), request.minimumPassRate(), request.releaseGateRequired(),
                CompatibilityMatrixStatus.QUEUED, request.dataSource(), request.scenario(), request.timeoutMs(),
                combinations.size(), 0, 0, 0, 0, QualityGateStatus.BLOCKED,
                List.of("EVALUATION_NOT_COMPLETED"), actor.userId(), now, null);
        List<CompatibilityMatrixCase> cases = new ArrayList<>();
        for (int index = 0; index < combinations.size(); index++) {
            CompatibilityMatrixCombination combination = combinations.get(index);
            cases.add(new CompatibilityMatrixCase(matrixRunId + "-case-" + index, matrixRunId, "",
                    combination.runtimeId(), combination.mcpServerId(), combination.llmProviderId(),
                    version(environments, ExecutionEnvironmentKind.AGENT_RUNTIME, combination.runtimeId()),
                    version(environments, ExecutionEnvironmentKind.MCP_SERVER, combination.mcpServerId()),
                    version(environments, ExecutionEnvironmentKind.LLM_PROVIDER, combination.llmProviderId()),
                    status(environments, ExecutionEnvironmentKind.AGENT_RUNTIME, combination.runtimeId()),
                    status(environments, ExecutionEnvironmentKind.MCP_SERVER, combination.mcpServerId()),
                    status(environments, ExecutionEnvironmentKind.LLM_PROVIDER, combination.llmProviderId()),
                    CompatibilityMatrixCaseStatus.QUEUED, 0, QualityGateStatus.BLOCKED,
                    List.of("EVALUATION_NOT_COMPLETED"), "", null, null,
                    snapshot(environments, ExecutionEnvironmentKind.AGENT_RUNTIME, combination.runtimeId()),
                    snapshot(environments, ExecutionEnvironmentKind.MCP_SERVER, combination.mcpServerId()),
                    snapshot(environments, ExecutionEnvironmentKind.LLM_PROVIDER, combination.llmProviderId())));
        }
        evidenceRepository.update(current -> {
            if (request.releaseGateRequired() && current.matrixRuns().stream().anyMatch(existing ->
                    existing.releaseGateRequired() && existing.skillId().equals(request.skillId())
                            && existing.skillVersion().equals(request.skillVersion())
                            && (existing.status() == CompatibilityMatrixStatus.QUEUED
                            || existing.status() == CompatibilityMatrixStatus.RUNNING))) {
                throw new CompatibilityMatrixConflictException("an active release compatibility matrix already exists");
            }
            List<CompatibilityMatrixRun> runs = new ArrayList<>(current.matrixRuns());
            runs.add(run);
            List<CompatibilityMatrixCase> matrixCases = new ArrayList<>(current.matrixCases());
            matrixCases.addAll(cases);
            return new QualityEvidenceState(current.suites(), current.rules(), current.runs(), current.snapshots(),
                    current.caseResults(), runs, matrixCases);
        });
        audit("COMPATIBILITY_MATRIX_CREATED", run, actor, requestId);
        startOrchestration(matrixRunId);
        return run;
    }

    @PostConstruct
    void resumeActiveMatrices() {
        evidenceRepository.load().matrixRuns().stream()
                .filter(run -> run.status() == CompatibilityMatrixStatus.QUEUED
                        || run.status() == CompatibilityMatrixStatus.RUNNING)
                .map(CompatibilityMatrixRun::matrixRunId)
                .forEach(this::startOrchestration);
    }

    private void startOrchestration(String matrixRunId) {
        if (!activeWorkers.add(matrixRunId)) return;
        CompletableFuture.runAsync(() -> {
            try {
                orchestrate(matrixRunId);
            } finally {
                activeWorkers.remove(matrixRunId);
            }
        });
    }

    public List<CompatibilityMatrixRun> list(String skillId, String skillVersion, String status,
                                             String dataSource, Actor actor) {
        requireAdmin(actor);
        return evidenceRepository.load().matrixRuns().stream()
                .filter(run -> skillId == null || skillId.isBlank() || skillId.equals(run.skillId()))
                .filter(run -> skillVersion == null || skillVersion.isBlank() || skillVersion.equals(run.skillVersion()))
                .filter(run -> status == null || status.isBlank() || run.status().name().equalsIgnoreCase(status.trim()))
                .filter(run -> dataSource == null || dataSource.isBlank() || run.dataSource().equalsIgnoreCase(dataSource.trim()))
                .sorted(Comparator.comparing(CompatibilityMatrixRun::createdAt).reversed())
                .toList();
    }

    public CompatibilityMatrixRun find(String matrixRunId, Actor actor) {
        requireAdmin(actor);
        return findInternal(matrixRunId, evidenceRepository.load());
    }

    public List<CompatibilityMatrixCase> cases(String matrixRunId, Actor actor) {
        requireAdmin(actor);
        QualityEvidenceState state = evidenceRepository.load();
        findInternal(matrixRunId, state);
        return state.matrixCases().stream().filter(matrixCase -> matrixRunId.equals(matrixCase.matrixRunId()))
                .sorted(Comparator.comparing(CompatibilityMatrixCase::caseId)).toList();
    }

    public CompatibilityMatrixRun cancel(String matrixRunId, Actor actor, String requestId) {
        requireAdmin(actor);
        CompatibilityMatrixRun current = find(matrixRunId, actor);
        if (current.status().terminal()) return current;
        cancellationRequests.add(matrixRunId);
        currentCases(matrixRunId).stream()
                .filter(matrixCase -> !matrixCase.status().terminal() && !matrixCase.evaluationRunId().isBlank())
                .map(CompatibilityMatrixCase::evaluationRunId)
                .forEach(this::cancelChildEvaluation);
        evidenceRepository.update(state -> {
            CompatibilityMatrixRun run = findInternal(matrixRunId, state);
            Instant now = clock.instant();
            List<CompatibilityMatrixCase> updatedCases = new ArrayList<>();
            int completed = 0;
            int passed = 0;
            for (CompatibilityMatrixCase matrixCase : state.matrixCases()) {
                if (!matrixRunId.equals(matrixCase.matrixRunId())) {
                    updatedCases.add(matrixCase);
                    continue;
                }
                CompatibilityMatrixCase updated = matrixCase;
                if (!matrixCase.status().terminal()) {
                    updated = caseResult(matrixCase, CompatibilityMatrixCaseStatus.CANCELLED, matrixCase.evaluationRunId(),
                            matrixCase.score(), QualityGateStatus.BLOCKED, List.of("COMPATIBILITY_MATRIX_CANCELLED"),
                            "COMPATIBILITY_MATRIX_CANCELLED", matrixCase.startedAt(), now);
                }
                if (updated.status().terminal()) completed++;
                if (updated.status() == CompatibilityMatrixCaseStatus.COMPLETED
                        && updated.gateStatus() == QualityGateStatus.PASSED) passed++;
                updatedCases.add(updated);
            }
            CompatibilityMatrixRun updatedRun = runResult(run, CompatibilityMatrixStatus.CANCELLED, completed, passed,
                    score(updatedCases, matrixRunId), passRate(passed, run.totalCases()),
                    QualityGateStatus.BLOCKED, List.of("COMPATIBILITY_MATRIX_CANCELLED"), now);
            return replace(state, updatedRun, updatedCases);
        });
        CompatibilityMatrixRun cancelled = findInternal(matrixRunId, evidenceRepository.load());
        audit("COMPATIBILITY_MATRIX_CANCELLED", cancelled, actor, requestId);
        return cancelled;
    }

    private void orchestrate(String matrixRunId) {
        try {
            markRunning(matrixRunId);
            List<CompatibilityMatrixCase> pending = casesFor(matrixRunId);
            CompatibilityMatrixRun run = runFromStore(matrixRunId);
            for (CompatibilityMatrixCase matrixCase : pending) {
                if (cancellationRequests.contains(matrixRunId)) return;
                if (matrixCase.status() != CompatibilityMatrixCaseStatus.QUEUED) continue;
                if (!active(matrixCase)) {
                    updateCase(matrixRunId, matrixCase.caseId(), caseResult(matrixCase,
                            CompatibilityMatrixCaseStatus.FAILED, "", 0, QualityGateStatus.BLOCKED,
                            List.of("EXECUTION_ENVIRONMENT_NOT_ACTIVE"), "EXECUTION_ENVIRONMENT_NOT_ACTIVE", null, clock.instant()));
                    continue;
                }
                EvaluationRun child = evaluationService.submit(new EvaluationRequest(run.skillId(), run.skillVersion(),
                        run.suiteId(), run.scenario(), run.timeoutMs(), matrixCase.runtimeId(),
                        matrixCase.mcpServerId(), matrixCase.llmProviderId(), run.suiteVersion()));
                updateCase(matrixRunId, matrixCase.caseId(), caseResult(matrixCase,
                        CompatibilityMatrixCaseStatus.RUNNING, child.id(), 0, QualityGateStatus.BLOCKED,
                        List.of("EVALUATION_NOT_COMPLETED"), "", clock.instant(), null));
                EvaluationRun terminal = evaluationService.awaitTerminal(child.id(),
                        Duration.ofMillis(Math.max(1_000, run.timeoutMs() + 5_000L)));
                if (!terminalStatus(terminal.status())) {
                    evaluationService.cancel(child.id());
                    terminal = evaluationService.awaitTerminal(child.id(), Duration.ofSeconds(1));
                }
                updateCaseFromEvaluation(matrixRunId, matrixCase.caseId(), terminal);
            }
            finish(matrixRunId);
        } catch (RuntimeException failure) {
            fail(matrixRunId, "COMPATIBILITY_MATRIX_ORCHESTRATION_FAILED");
        } finally {
            cancellationRequests.remove(matrixRunId);
        }
    }

    private void markRunning(String matrixRunId) {
        evidenceRepository.update(state -> {
            CompatibilityMatrixRun run = findInternal(matrixRunId, state);
            if (run.status().terminal()) return state;
            return replace(state, runResult(run, CompatibilityMatrixStatus.RUNNING, run.completedCases(), run.passedCases(),
                    run.score(), run.passRate(), run.gateStatus(), run.gateReasons(), null), state.matrixCases());
        });
    }

    private void updateCaseFromEvaluation(String matrixRunId, String caseId, EvaluationRun evaluation) {
        CompatibilityMatrixCaseStatus status = switch (evaluation.status()) {
            case COMPLETED -> CompatibilityMatrixCaseStatus.COMPLETED;
            case FAILED -> CompatibilityMatrixCaseStatus.FAILED;
            case TIMED_OUT -> CompatibilityMatrixCaseStatus.TIMED_OUT;
            case CANCELLED -> CompatibilityMatrixCaseStatus.CANCELLED;
            default -> CompatibilityMatrixCaseStatus.FAILED;
        };
        List<String> reasons = evaluation.gateReasons() == null || evaluation.gateReasons().isEmpty()
                ? List.of(evaluation.errorCode() == null || evaluation.errorCode().isBlank() ? status.name() : evaluation.errorCode())
                : evaluation.gateReasons();
        updateCase(matrixRunId, caseId, caseResult(findCase(matrixRunId, caseId), status, evaluation.id(),
                evaluation.score(), evaluation.gateStatus(), reasons, safeError(evaluation.errorCode()),
                findCase(matrixRunId, caseId).startedAt(), evaluation.completedAt() == null ? clock.instant() : evaluation.completedAt()));
    }

    private void finish(String matrixRunId) {
        evidenceRepository.update(state -> {
            CompatibilityMatrixRun run = findInternal(matrixRunId, state);
            if (run.status() == CompatibilityMatrixStatus.CANCELLED) return state;
            List<CompatibilityMatrixCase> matrixCases = state.matrixCases().stream()
                    .filter(matrixCase -> matrixRunId.equals(matrixCase.matrixRunId())).toList();
            int completed = (int) matrixCases.stream().filter(matrixCase -> matrixCase.status().terminal()).count();
            int passed = (int) matrixCases.stream().filter(matrixCase -> matrixCase.status() == CompatibilityMatrixCaseStatus.COMPLETED
                    && matrixCase.gateStatus() == QualityGateStatus.PASSED).count();
            if (completed != run.totalCases()) {
                return replace(state, runResult(run, CompatibilityMatrixStatus.FAILED, completed, passed,
                        score(matrixCases, matrixRunId), passRate(passed, run.totalCases()), QualityGateStatus.BLOCKED,
                        List.of("COMPATIBILITY_MATRIX_INCOMPLETE"), clock.instant()), state.matrixCases());
            }
            double passRate = passRate(passed, run.totalCases());
            boolean passedPolicy = run.policy() == CompatibilityMatrixPolicy.ALL_MUST_PASS
                    ? passed == run.totalCases()
                    : passRate >= run.minimumPassRate();
            QualityGateStatus gateStatus = passedPolicy ? QualityGateStatus.PASSED : QualityGateStatus.BLOCKED;
            List<String> reasons = passedPolicy ? List.of() : List.of("COMPATIBILITY_MATRIX_BLOCKED");
            return replace(state, runResult(run, CompatibilityMatrixStatus.COMPLETED, completed, passed,
                    score(matrixCases, matrixRunId), passRate, gateStatus, reasons, clock.instant()), state.matrixCases());
        });
    }

    private void fail(String matrixRunId, String errorCode) {
        try {
            evidenceRepository.update(state -> {
                CompatibilityMatrixRun run = findInternal(matrixRunId, state);
                Instant now = clock.instant();
                List<CompatibilityMatrixCase> updatedCases = new ArrayList<>();
                int completed = 0;
                int passed = 0;
                for (CompatibilityMatrixCase matrixCase : state.matrixCases()) {
                    if (!matrixRunId.equals(matrixCase.matrixRunId())) {
                        updatedCases.add(matrixCase);
                        continue;
                    }
                    CompatibilityMatrixCase updated = matrixCase.status().terminal() ? matrixCase
                            : caseResult(matrixCase, CompatibilityMatrixCaseStatus.FAILED, matrixCase.evaluationRunId(),
                            matrixCase.score(), QualityGateStatus.BLOCKED, List.of(errorCode), errorCode,
                            matrixCase.startedAt(), now);
                    if (updated.status().terminal()) completed++;
                    if (updated.status() == CompatibilityMatrixCaseStatus.COMPLETED
                            && updated.gateStatus() == QualityGateStatus.PASSED) passed++;
                    updatedCases.add(updated);
                }
                return replace(state, runResult(run, CompatibilityMatrixStatus.FAILED, completed, passed,
                        score(updatedCases, matrixRunId), passRate(passed, run.totalCases()), QualityGateStatus.BLOCKED,
                        List.of(errorCode), now), updatedCases);
            });
        } catch (RuntimeException ignored) {
            // Persistence errors are surfaced by the repository and retained as the stable API error.
        }
    }

    private void updateCase(String matrixRunId, String caseId, CompatibilityMatrixCase replacement) {
        evidenceRepository.update(state -> {
            CompatibilityMatrixRun run = findInternal(matrixRunId, state);
            if (run.status() == CompatibilityMatrixStatus.CANCELLED) return state;
            List<CompatibilityMatrixCase> cases = state.matrixCases().stream()
                    .map(matrixCase -> matrixCase.caseId().equals(caseId) ? replacement : matrixCase).toList();
            int completed = (int) cases.stream().filter(matrixCase -> matrixRunId.equals(matrixCase.matrixRunId())
                    && matrixCase.status().terminal()).count();
            int passed = (int) cases.stream().filter(matrixCase -> matrixRunId.equals(matrixCase.matrixRunId())
                    && matrixCase.status() == CompatibilityMatrixCaseStatus.COMPLETED
                    && matrixCase.gateStatus() == QualityGateStatus.PASSED).count();
            CompatibilityMatrixRun updatedRun = runResult(run, run.status(), completed, passed,
                    score(cases, matrixRunId), passRate(passed, run.totalCases()), run.gateStatus(),
                    run.gateReasons(), run.completedAt());
            return replace(state, updatedRun, cases);
        });
    }

    private QualityEvidenceState replace(QualityEvidenceState state, CompatibilityMatrixRun run,
                                         List<CompatibilityMatrixCase> replacements) {
        List<CompatibilityMatrixRun> runs = state.matrixRuns().stream()
                .map(current -> current.matrixRunId().equals(run.matrixRunId()) ? run : current).toList();
        List<CompatibilityMatrixCase> cases = replacements == state.matrixCases() ? state.matrixCases() : replacements;
        return new QualityEvidenceState(state.suites(), state.rules(), state.runs(), state.snapshots(),
                state.caseResults(), runs, cases);
    }

    private CompatibilityMatrixRun runResult(CompatibilityMatrixRun run, CompatibilityMatrixStatus status,
                                             int completed, int passed, int score, double passRate,
                                             QualityGateStatus gateStatus, List<String> reasons, Instant completedAt) {
        return new CompatibilityMatrixRun(run.matrixRunId(), run.skillId(), run.skillVersion(), run.suiteId(),
                run.suiteVersion(), run.policy(), run.minimumPassRate(), run.releaseGateRequired(), status,
                run.dataSource(), run.scenario(), run.timeoutMs(), run.totalCases(), completed, passed, score,
                passRate, gateStatus, reasons, run.createdBy(), run.createdAt(), completedAt);
    }

    private CompatibilityMatrixCase caseResult(CompatibilityMatrixCase current, CompatibilityMatrixCaseStatus status,
                                               String evaluationRunId, int score, QualityGateStatus gateStatus,
                                               List<String> reasons, String errorCode, Instant startedAt, Instant completedAt) {
        return new CompatibilityMatrixCase(current.caseId(), current.matrixRunId(), evaluationRunId,
                current.runtimeId(), current.mcpServerId(), current.llmProviderId(), current.runtimeVersion(),
                current.mcpServerVersion(), current.llmProviderVersion(), current.runtimeStatus(),
                current.mcpServerStatus(), current.llmProviderStatus(), status, score, gateStatus, reasons,
                errorCode, startedAt, completedAt, current.runtimeEnvironment(), current.mcpServerEnvironment(),
                current.llmProviderEnvironment());
    }

    private Map<String, ExecutionEnvironment> snapshotEnvironments(List<CompatibilityMatrixCombination> combinations) {
        Map<String, ExecutionEnvironment> result = new HashMap<>();
        for (CompatibilityMatrixCombination combination : combinations) {
            addEnvironment(result, ExecutionEnvironmentKind.AGENT_RUNTIME, combination.runtimeId());
            addEnvironment(result, ExecutionEnvironmentKind.MCP_SERVER, combination.mcpServerId());
            addEnvironment(result, ExecutionEnvironmentKind.LLM_PROVIDER, combination.llmProviderId());
        }
        return result;
    }

    private void addEnvironment(Map<String, ExecutionEnvironment> result, ExecutionEnvironmentKind kind, String id) {
        if (id == null || id.isBlank()) return;
        ExecutionEnvironment environment = environmentService.requireActive(kind, id);
        result.put(kind.name() + "/" + id, environment);
    }

    private String version(Map<String, ExecutionEnvironment> environments, ExecutionEnvironmentKind kind, String id) {
        ExecutionEnvironment environment = environments.get(kind.name() + "/" + id);
        return environment == null ? "" : environment.version();
    }

    private com.huawei.skillcenter.execution.ExecutionEnvironmentStatus status(Map<String, ExecutionEnvironment> environments,
                                                                                ExecutionEnvironmentKind kind, String id) {
        ExecutionEnvironment environment = environments.get(kind.name() + "/" + id);
        return environment == null ? null : environment.status();
    }

    private CompatibilityMatrixEnvironmentSnapshot snapshot(Map<String, ExecutionEnvironment> environments,
                                                             ExecutionEnvironmentKind kind, String id) {
        ExecutionEnvironment environment = environments.get(kind.name() + "/" + id);
        if (environment == null) return CompatibilityMatrixEnvironmentSnapshot.empty();
        return CompatibilityMatrixEnvironmentSnapshot.of(environment.environmentId(), environment.version(),
                environment.status(), environment.capabilities(), environment.adapterProviderId());
    }

    private boolean active(CompatibilityMatrixCase matrixCase) {
        try {
            if (!matrixCase.runtimeId().isBlank()) environmentService.requireActive(ExecutionEnvironmentKind.AGENT_RUNTIME, matrixCase.runtimeId());
            if (!matrixCase.mcpServerId().isBlank()) environmentService.requireActive(ExecutionEnvironmentKind.MCP_SERVER, matrixCase.mcpServerId());
            if (!matrixCase.llmProviderId().isBlank()) environmentService.requireActive(ExecutionEnvironmentKind.LLM_PROVIDER, matrixCase.llmProviderId());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private List<CompatibilityMatrixCase> casesFor(String matrixRunId) {
        return evidenceRepository.load().matrixCases().stream().filter(matrixCase -> matrixRunId.equals(matrixCase.matrixRunId()))
                .sorted(Comparator.comparing(CompatibilityMatrixCase::caseId)).toList();
    }

    private List<CompatibilityMatrixCase> currentCases(String matrixRunId) {
        return evidenceRepository.load().matrixCases().stream()
                .filter(matrixCase -> matrixRunId.equals(matrixCase.matrixRunId()))
                .toList();
    }

    private void cancelChildEvaluation(String evaluationRunId) {
        try {
            evaluationService.cancel(evaluationRunId);
        } catch (RuntimeException ignored) {
            // The matrix still reaches a stable cancelled state if a child already disappeared.
        }
    }

    private CompatibilityMatrixCase findCase(String matrixRunId, String caseId) {
        return evidenceRepository.load().matrixCases().stream()
                .filter(matrixCase -> matrixRunId.equals(matrixCase.matrixRunId()) && caseId.equals(matrixCase.caseId()))
                .findFirst().orElseThrow(() -> new CompatibilityMatrixNotFoundException(matrixRunId));
    }

    private CompatibilityMatrixRun runFromStore(String matrixRunId) {
        return findInternal(matrixRunId, evidenceRepository.load());
    }

    private CompatibilityMatrixRun findInternal(String matrixRunId, QualityEvidenceState state) {
        return state.matrixRuns().stream().filter(run -> matrixRunId.equals(run.matrixRunId())).findFirst()
                .orElseThrow(() -> new CompatibilityMatrixNotFoundException(matrixRunId));
    }

    private int score(List<CompatibilityMatrixCase> cases, String matrixRunId) {
        List<CompatibilityMatrixCase> selected = cases.stream().filter(matrixCase -> matrixRunId.equals(matrixCase.matrixRunId())).toList();
        return selected.isEmpty() ? 0 : (int) Math.round(selected.stream().mapToInt(CompatibilityMatrixCase::score).average().orElse(0));
    }

    private double passRate(int passed, int total) {
        return total == 0 ? 0 : (double) passed / total;
    }

    private String safeError(String errorCode) {
        return errorCode == null ? "" : errorCode;
    }

    private boolean terminalStatus(EvaluationRunStatus status) {
        return status == EvaluationRunStatus.COMPLETED
                || status == EvaluationRunStatus.FAILED
                || status == EvaluationRunStatus.TIMED_OUT
                || status == EvaluationRunStatus.CANCELLED;
    }

    private void audit(String action, CompatibilityMatrixRun run, Actor actor, String requestId) {
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(), action, "COMPATIBILITY_MATRIX",
                run.matrixRunId(), actor.userId(), actor.role(), requestId, clock.instant(),
                Map.of("skillId", run.skillId(), "skillVersion", run.skillVersion(),
                        "status", run.status().name(), "totalCases", String.valueOf(run.totalCases()),
                        "releaseGateRequired", String.valueOf(run.releaseGateRequired()))));
    }

    private void requireAdmin(Actor actor) {
        RoleGuard.require(actor, Set.of("admin"));
    }
}
