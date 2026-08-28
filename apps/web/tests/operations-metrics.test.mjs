import test from "node:test";
import assert from "node:assert/strict";
import { createSkillApi } from "../src/api/skillApi.js";
import { getNavigationForRole } from "../src/state.js";
import { normalizeLifecycleProjectionReconciliation, normalizeOperationsAlerts, normalizeOperationsMetrics, normalizePlatformReadiness, normalizeProductionEvidence, normalizeReleaseTargetProbe, normalizeReleaseTargetProbes, normalizeRuntimeOperations, normalizeTraceObservations } from "../src/operationsMetrics.js";
import { normalizeExecutionEnvironmentSnapshot, normalizeOptimizationSuggestionThresholds, normalizeQualityEvaluationRun, normalizeQualityRules, normalizeQualitySnapshot, normalizeSkillExecutionRecord } from "../src/quality.js";
import { normalizeQualityBenchmarks, normalizeQualityComparison, normalizeQualitySuggestions, normalizeSkillQualityDetail } from "../src/qualityDetail.js";

test("skillApi exposes aggregated platform readiness", async () => {
  let capturedPath = "";
  const api = createSkillApi((path) => { capturedPath = path; return Promise.resolve({ data: {} }); });

  await api.getPlatformReadiness();

  assert.equal(capturedPath, "/api/v1/admin/platform/readiness");
});

test("skillApi exposes production evidence ledger read and update routes", async () => {
  let captured = null;
  const api = createSkillApi((path, options) => { captured = { path, options }; return Promise.resolve({ data: {} }); });

  await api.listProductionEvidence();
  assert.deepEqual(captured, { path: "/api/v1/admin/platform/evidence", options: undefined });
  await api.updateProductionEvidence("DATABASE_CAPACITY_SLO", { status: "ACCEPTED", expectedRevision: 0 });
  assert.equal(captured.path, "/api/v1/admin/platform/evidence/DATABASE_CAPACITY_SLO");
  assert.equal(captured.options.method, "PUT");
  assert.deepEqual(JSON.parse(captured.options.body), { status: "ACCEPTED", expectedRevision: 0 });
});

test("skillApi probes release target connectivity without a request body", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.probeReleaseTarget();

  assert.equal(calls[0].path, "/api/v1/admin/platform/release-target/probe");
  assert.equal(calls[0].options.method, "POST");
  assert.equal(calls[0].options.body, undefined);
});

test("skillApi lists bounded release target probe history", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: [] });
  });

  await api.listReleaseTargetProbes(3);

  assert.equal(calls[0].path, "/api/v1/admin/platform/release-target/probes?limit=3");
  assert.equal(calls[0].options.method, undefined);
});

test("platform readiness normalization keeps only safe bounded metadata", () => {
  const result = normalizePlatformReadiness({ data: {
    overall: "NOT_READY",
    scope: "PRODUCTION_HANDOFF",
    components: [{ componentId: "PRODUCTION_EXTERNAL_EVIDENCE", status: "NOT_READY", reasonCode: "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED", summary: "待核验" }],
    blockingReasonCodes: ["PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED"],
    endpoint: "https://secret.example",
  } });

  assert.equal(result.overall, "NOT_READY");
  assert.equal(result.components[0].componentId, "PRODUCTION_EXTERNAL_EVIDENCE");
  assert.equal(result.endpoint, undefined);
  assert.deepEqual(normalizePlatformReadiness(null).components, []);
});

test("release target probe normalization keeps only safe status metadata", () => {
  const result = normalizeReleaseTargetProbe({ data: {
    targetId: "release-target", status: "REACHABLE", reasonCode: "PROBE_OK",
    httpStatus: 204, latencyMs: 12, checkedAt: "2026-08-25T00:00:00Z",
    endpoint: "https://secret.example", credentialRef: "secret://env/TOKEN",
  } });

  assert.equal(result.status, "REACHABLE");
  assert.equal(result.httpStatus, 204);
  assert.equal(result.endpoint, undefined);
  assert.equal(result.credentialRef, undefined);
  assert.equal(normalizeReleaseTargetProbe(null).status, "UNKNOWN");
});

test("release target probe history normalization keeps safe bounded entries", () => {
  const result = normalizeReleaseTargetProbes({ data: [
    { targetId: "release-target", status: "TIMEOUT", reasonCode: "PROBE_TIMEOUT", latencyMs: 1500,
      endpoint: "https://secret.example", credentialRef: "secret://env/TOKEN" },
  ] });

  assert.equal(result.length, 1);
  assert.equal(result[0].status, "TIMEOUT");
  assert.equal(result[0].endpoint, undefined);
  assert.deepEqual(normalizeReleaseTargetProbes(null), []);
});

test("production evidence normalization keeps only safe ledger metadata", () => {
  const result = normalizeProductionEvidence({ data: [{
    evidenceId: "DATABASE_CAPACITY_SLO", status: "ACCEPTED", ownerUserId: "admin",
    verifiedAt: "2026-08-25T00:00:00Z", expiresAt: "2026-09-01T00:00:00Z",
    evidenceRef: "change-1", summary: "validated", revision: 2,
    credential: "secret", reportBody: "private",
  }] });

  assert.equal(result.length, 1);
  assert.equal(result[0].evidenceId, "DATABASE_CAPACITY_SLO");
  assert.equal(result[0].credential, undefined);
  assert.equal(result[0].reportBody, undefined);
  assert.deepEqual(normalizeProductionEvidence(null), []);
});

test("skillApi.getOperationsMetrics encodes the selected window", async () => {
  let capturedPath = "";
  const api = createSkillApi((path) => {
    capturedPath = path;
    return Promise.resolve({ data: {} });
  });

  await api.getOperationsMetrics("5m");

  assert.equal(capturedPath, "/api/v1/admin/operations/metrics?window=5m");
});

test("skillApi.getOperationsAlerts encodes the selected window", async () => {
  let capturedPath = "";
  const api = createSkillApi((path) => {
    capturedPath = path;
    return Promise.resolve({ data: [] });
  });

  await api.getOperationsAlerts("60m");

  assert.equal(capturedPath, "/api/v1/admin/operations/alerts?window=60m");
});

test("skillApi.getSkillLifecycleProjectionReconciliation uses the admin projection endpoint", async () => {
  let capturedPath = "";
  const api = createSkillApi((path) => {
    capturedPath = path;
    return Promise.resolve({ data: {} });
  });

  await api.getSkillLifecycleProjectionReconciliation();

  assert.equal(capturedPath, "/api/v1/admin/skill-lifecycle/projection/reconciliation");
});

test("normalizes lifecycle reconciliation state and count deltas for operations", () => {
  assert.deepEqual(normalizeLifecycleProjectionReconciliation({ data: {
    backend: "postgresql",
    state: "DRIFTED",
    reasonCode: "SKILL_LIFECYCLE_PROJECTION_COUNT_MISMATCH",
    revision: 4,
    projectionAgeSeconds: 901,
    maxProjectionAgeSeconds: 900,
    sourceCounts: { skillCount: 3, versionCount: 5, releaseCount: 2, scopeCount: 2, relationCount: 1 },
    projectedCounts: { skillCount: 2, versionCount: 4, releaseCount: 2, scopeCount: 2, relationCount: 1 },
    countDelta: { skillCount: 1, versionCount: 1, releaseCount: 0, scopeCount: 0, relationCount: 0 },
  } }), {
    backend: "postgresql",
    state: "DRIFTED",
    reasonCode: "SKILL_LIFECYCLE_PROJECTION_COUNT_MISMATCH",
    revision: 4,
    projectionAgeSeconds: 901,
    maxProjectionAgeSeconds: 900,
    sourceCounts: { skillCount: 3, versionCount: 5, releaseCount: 2, scopeCount: 2, relationCount: 1 },
    projectedCounts: { skillCount: 2, versionCount: 4, releaseCount: 2, scopeCount: 2, relationCount: 1 },
    countDelta: { skillCount: 1, versionCount: 1, releaseCount: 0, scopeCount: 0, relationCount: 0 },
  });
});

test("skillApi.getSkillRuntimeMetrics encodes filters and source", async () => {
  let capturedPath = "";
  const api = createSkillApi((path) => {
    capturedPath = path;
    return Promise.resolve({ data: {} });
  });

  await api.getSkillRuntimeMetrics({ window: "24h", skillId: "skill-a", version: "1.0.0", dataSource: "mock" });

  assert.equal(capturedPath, "/api/v1/admin/operations/skill-runtime?window=24h&skillId=skill-a&version=1.0.0&dataSource=mock");
});

test("skillApi.getTraces encodes failure-location filters", async () => {
  let capturedPath = "";
  const api = createSkillApi((path) => {
    capturedPath = path;
    return Promise.resolve({ data: [] });
  });

  await api.getTraces({ window: "24h", skillId: "skill-a", version: "1.0.0", status: "failure", dataSource: "production" });

  assert.equal(capturedPath, "/api/v1/admin/operations/traces?window=24h&skillId=skill-a&version=1.0.0&status=failure&dataSource=production");
});

test("only admin navigation exposes operations monitoring", () => {
  assert.ok(getNavigationForRole("admin").includes("operations"));
  assert.equal(getNavigationForRole("viewer").includes("operations"), false);
  assert.equal(getNavigationForRole("reviewer").includes("operations"), false);
});

test("only admin navigation exposes quality management", () => {
  assert.ok(getNavigationForRole("admin").includes("quality"));
  assert.equal(getNavigationForRole("developer").includes("quality"), false);
});

test("skillApi can submit and list quality evaluations", async () => {
  const paths = [];
  const api = createSkillApi((path, options = {}) => {
    paths.push({ path, options });
    return Promise.resolve({ data: { id: "run-1" } });
  });

  await api.listQualityEvaluations("eox-query", { dataSource: "mock", runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway" });
  await api.submitQualityEvaluation({ skillId: "eox-query", skillVersion: "1.2.0", suiteId: "smoke" });
  await api.cancelQualityEvaluation("run-1");
  await api.executeSkill({ skillId: "eox-query", skillVersion: "1.2.0", scenario: "success", timeoutMs: 1000 });
  await api.getSkillExecution("execution-1");
  await api.listSkillExecutions("eox-query", { dataSource: "production", runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway" });
  await api.getQualityEvaluationResults("run-1");

  assert.equal(paths[0].path, "/api/v1/admin/quality/evaluations?skillId=eox-query&dataSource=mock&runtimeId=openclaw&mcpServerId=mcp-network&llmProviderId=llm-gateway");
  assert.equal(paths[1].path, "/api/v1/admin/quality/evaluations");
  assert.equal(paths[1].options.method, "POST");
  assert.equal(paths[2].path, "/api/v1/admin/quality/evaluations/run-1/cancel");
  assert.equal(paths[2].options.method, "POST");
  assert.equal(paths[3].path, "/api/v1/admin/runner/executions");
  assert.equal(paths[3].options.method, "POST");
  assert.equal(paths[4].path, "/api/v1/admin/runner/executions/execution-1");
  assert.equal(paths[5].path, "/api/v1/admin/runner/executions?skillId=eox-query&dataSource=production&runtimeId=openclaw&mcpServerId=mcp-network&llmProviderId=llm-gateway");
  assert.equal(paths[6].path, "/api/v1/admin/quality/evaluations/run-1/results");
});

test("skillApi exposes quality suite and rule management", async () => {
  const paths = [];
  const api = createSkillApi((path, options = {}) => {
    paths.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.listQualitySuites();
  await api.getQualityRules();
  await api.createQualitySuite({ id: "release", name: "Release", version: "release-v1", enabled: true, cases: [{ id: "case-1", name: "成功" }] });
  await api.updateQualityRules({ id: "default", version: "quality-v2", minScore: 90, minPassRate: 1, minStaticScore: 95 });

  assert.equal(paths[0].path, "/api/v1/admin/quality/suites");
  assert.equal(paths[1].path, "/api/v1/admin/quality/rules");
  assert.equal(paths[2].options.method, "POST");
  assert.equal(paths[3].options.method, "PUT");
});

test("skillApi exposes optimization suggestion threshold governance", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.getOptimizationSuggestionThresholds();
  await api.updateOptimizationSuggestionThresholds({ minSuccessRatePercent: 97, maxP95Ms: 900, minRuntimeSamples: 8 });

  assert.equal(calls[0].path, "/api/v1/admin/quality/suggestion-thresholds");
  assert.equal(calls[1].path, "/api/v1/admin/quality/suggestion-thresholds");
  assert.equal(calls[1].options.method, "PUT");
  assert.deepEqual(JSON.parse(calls[1].options.body), { minSuccessRatePercent: 97, maxP95Ms: 900, minRuntimeSamples: 8 });
});

test("skillApi exposes optimization work item lifecycle endpoints", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.listOptimizationWorkItems({ skillId: "skill-a", status: "OPEN", ownerId: "admin", sourceVersion: "1.0.0" });
  await api.createOptimizationWorkItem({ skillId: "skill-a", sourceVersion: "1.0.0", suggestionId: "runtime-data", hypothesis: "降低超时", ownerId: "admin", dataSource: "mock", runtimeId: "openclaw" });
  await api.updateOptimizationWorkItemStatus("work-1", { status: "PLANNED" });
  await api.bindOptimizationWorkItemEvidence("work-1", { evidenceType: "BENCHMARK", evidenceId: "benchmark-1", outcome: "改善" });
  await api.getOptimizationWorkItemHealth();

  assert.equal(calls[0].path, "/api/v1/admin/quality/optimization-work-items?skillId=skill-a&status=OPEN&ownerId=admin&sourceVersion=1.0.0");
  assert.equal(calls[1].path, "/api/v1/admin/quality/optimization-work-items");
  assert.equal(calls[1].options.method, "POST");
  assert.deepEqual(JSON.parse(calls[1].options.body).suggestionId, "runtime-data");
  assert.equal(calls[2].path, "/api/v1/admin/quality/optimization-work-items/work-1/status");
  assert.equal(calls[2].options.method, "PATCH");
  assert.equal(calls[3].path, "/api/v1/admin/quality/optimization-work-items/work-1/evidence");
  assert.equal(calls[3].options.method, "PUT");
  assert.deepEqual(JSON.parse(calls[3].options.body), { evidenceType: "BENCHMARK", evidenceId: "benchmark-1", outcome: "改善" });
  assert.equal(calls[4].path, "/api/v1/admin/quality/optimization-work-items/health");
});

test("skillApi exposes reserved external provider contracts", async () => {
  const paths = [];
  const api = createSkillApi((path) => {
    paths.push(path);
    return Promise.resolve({ data: [] });
  });

  await api.listQualityProviderContracts();

  assert.equal(paths[0], "/api/v1/admin/quality/provider-contracts");
});

test("skillApi exposes active provider readiness status", async () => {
  const paths = [];
  const api = createSkillApi((path) => {
    paths.push(path);
    return Promise.resolve({ data: [] });
  });

  await api.listQualityProviders();

  assert.equal(paths[0], "/api/v1/admin/quality/providers");
});

test("skillApi exposes provider readiness summary", async () => {
  const paths = [];
  const api = createSkillApi((path) => {
    paths.push(path);
    return Promise.resolve({ data: {} });
  });

  await api.getQualityProviderReadiness();

  assert.equal(paths[0], "/api/v1/admin/quality/provider-readiness");
});

test("skillApi lists managed execution environments with filters", async () => {
  const paths = [];
  const api = createSkillApi((path) => {
    paths.push(path);
    return Promise.resolve({ data: [] });
  });

  await api.listExecutionEnvironments({ kind: "AGENT_RUNTIME", status: "ACTIVE" });

  assert.equal(paths[0], "/api/v1/admin/execution-environments?kind=AGENT_RUNTIME&status=ACTIVE");
});

test("skillApi probes provider connectivity without a request body", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: [] });
  });

  await api.probeQualityProviders();

  assert.equal(calls[0].path, "/api/v1/admin/quality/provider-readiness/probe");
  assert.equal(calls[0].options.method, "POST");
  assert.equal(calls[0].options.body, undefined);
});

test("skillApi exposes detail quality and version comparison endpoints", async () => {
  const paths = [];
  const api = createSkillApi((path) => {
    paths.push(path);
    return Promise.resolve({ data: {} });
  });

  await api.getSkillQuality("skill-a", { version: "1.1.0", window: "24h" });
  await api.compareSkillQuality("skill-a", { baselineVersion: "1.0.0", candidateVersion: "1.1.0", window: "24h" });

  assert.equal(paths[0], "/api/v1/skills/skill-a/quality?version=1.1.0&window=24h");
  assert.equal(paths[1], "/api/v1/skills/skill-a/quality/compare?baselineVersion=1.0.0&candidateVersion=1.1.0&window=24h");
});

test("skillApi exposes benchmark creation and public evidence queries", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: [] });
  });

  await api.listQualityBenchmarks("skill-a");
  await api.listQualityBenchmarks("skill-a", { dataSource: "production", runtimeId: "openclaw" });
  await api.createQualityBenchmark({ skillId: "skill-a", baselineVersion: "1.0.0", candidateVersion: "1.1.0", window: "24h", dataSource: "mock", runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway" });
  await api.getSkillQualityBenchmarks("skill-a", { version: "1.1.0" });

  assert.equal(calls[0].path, "/api/v1/admin/quality/benchmarks?skillId=skill-a");
  assert.equal(calls[1].path, "/api/v1/admin/quality/benchmarks?skillId=skill-a&dataSource=production&runtimeId=openclaw");
  assert.equal(calls[2].path, "/api/v1/admin/quality/benchmarks");
  assert.equal(calls[2].options.method, "POST");
  assert.deepEqual(JSON.parse(calls[2].options.body).runtimeId, "openclaw");
  assert.deepEqual(JSON.parse(calls[2].options.body).mcpServerId, "mcp-network");
  assert.deepEqual(JSON.parse(calls[2].options.body).llmProviderId, "llm-gateway");
  assert.equal(calls[3].path, "/api/v1/skills/skill-a/quality/benchmarks?version=1.1.0");
});

test("skillApi exposes traceable quality optimization suggestions", async () => {
  const paths = [];
  const api = createSkillApi((path) => {
    paths.push(path);
    return Promise.resolve({ data: [] });
  });

  await api.getSkillQualitySuggestions("skill-a", { version: "1.0.0", window: "24h", dataSource: "production" });

  assert.equal(paths[0], "/api/v1/skills/skill-a/quality/suggestions?version=1.0.0&window=24h&dataSource=production");
});

test("skillApi exposes compatibility matrix lifecycle endpoints", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.listQualityCompatibilityMatrices({ skillId: "skill-a", skillVersion: "1.1.0", dataSource: "mock" });
  await api.createQualityCompatibilityMatrix({ skillId: "skill-a" });
  await api.getQualityCompatibilityMatrix("matrix-1");
  await api.getQualityCompatibilityMatrixCases("matrix-1");
  await api.cancelQualityCompatibilityMatrix("matrix-1");

  assert.deepEqual(calls.map((call) => call.path), [
    "/api/v1/admin/quality/compatibility-matrices?skillId=skill-a&skillVersion=1.1.0&dataSource=mock",
    "/api/v1/admin/quality/compatibility-matrices",
    "/api/v1/admin/quality/compatibility-matrices/matrix-1",
    "/api/v1/admin/quality/compatibility-matrices/matrix-1/cases",
    "/api/v1/admin/quality/compatibility-matrices/matrix-1/cancel",
  ]);
  assert.equal(calls[1].options.method, "POST");
  assert.equal(calls[4].options.method, "POST");
});

test("skillApi carries execution environment filters through quality suggestions and disposition", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  const environment = { runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway" };
  await api.getSkillQualitySuggestions("skill-a", { version: "1.0.0", window: "24h", ...environment });
  await api.updateSkillQualitySuggestionDisposition("skill-a", "runtime-data", {
    version: "1.0.0", window: "24h", ...environment, status: "ACKNOWLEDGED",
  });

  assert.equal(calls[0].path, "/api/v1/skills/skill-a/quality/suggestions?version=1.0.0&window=24h&runtimeId=openclaw&mcpServerId=mcp-network&llmProviderId=llm-gateway");
  assert.equal(calls[1].path, "/api/v1/skills/skill-a/quality/suggestions/runtime-data/disposition?version=1.0.0&window=24h&runtimeId=openclaw&mcpServerId=mcp-network&llmProviderId=llm-gateway");
});

test("skillApi carries execution environment filters through public Benchmark evidence", async () => {
  const paths = [];
  const api = createSkillApi((path) => { paths.push(path); return Promise.resolve({ data: [] }); });
  await api.getSkillQualityBenchmarks("skill-a", {
    runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway",
  });
  assert.equal(paths[0], "/api/v1/skills/skill-a/quality/benchmarks?runtimeId=openclaw&mcpServerId=mcp-network&llmProviderId=llm-gateway");
});

test("skillApi updates an optimization suggestion disposition with an auditable request", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: { id: "runtime-data", dispositionStatus: "ACKNOWLEDGED" } });
  });

  await api.updateSkillQualitySuggestionDisposition("skill-a", "runtime-data", {
    version: "1.0.0", window: "24h", status: "ACKNOWLEDGED", note: "补充样本",
  });

  assert.equal(calls[0].path, "/api/v1/skills/skill-a/quality/suggestions/runtime-data/disposition?version=1.0.0&window=24h");
  assert.equal(calls[0].options.method, "PATCH");
  assert.deepEqual(JSON.parse(calls[0].options.body), { status: "ACKNOWLEDGED", note: "补充样本" });
});

test("quality normalization preserves gate reasons and safe defaults", () => {
  assert.deepEqual(normalizeQualityRules(null), { id: "default", version: "quality-v1", minScore: 80, minPassRate: 0.8, minStaticScore: 90 });
  assert.deepEqual(normalizeQualitySnapshot({ data: { score: 92, staticScore: 100, passRate: 1, gateStatus: "PASSED", gateReasons: [] } }), {
    score: 92, staticScore: 100, passRate: 1, gateStatus: "PASSED", gateReasons: [], dataSource: "mock", ruleVersion: "quality-v1",
  });
});

test("quality snapshot normalization preserves execution environment identifiers", () => {
  const snapshot = normalizeQualitySnapshot({ data: {
    snapshotId: "snapshot-1", runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway",
    runtimeEnvironment: { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE" },
  } });
  assert.equal(snapshot.snapshotId, "snapshot-1");
  assert.equal(snapshot.runtimeId, "openclaw");
  assert.equal(snapshot.mcpServerId, "mcp-network");
  assert.equal(snapshot.llmProviderId, "llm-gateway");
  assert.deepEqual(snapshot.runtimeEnvironment, {
    kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE",
  });
});

test("execution evidence normalization keeps complete snapshots and legacy IDs", () => {
  const runtime = { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE" };
  assert.deepEqual(normalizeExecutionEnvironmentSnapshot(runtime, "AGENT_RUNTIME"), runtime);
  assert.equal(normalizeExecutionEnvironmentSnapshot({ environmentId: "bad id", version: "secret" }), null);
  assert.deepEqual(normalizeQualityEvaluationRun({ data: {
    id: "run-1", status: "COMPLETED", runtimeId: "openclaw", runtimeEnvironment: runtime,
  } }).runtimeEnvironment, runtime);
  assert.deepEqual(normalizeSkillExecutionRecord({ data: {
    executionId: "exec-1", status: "SUCCEEDED", runtimeId: "openclaw", runtimeEnvironment: runtime,
  } }).runtimeEnvironment, runtime);
  assert.equal(normalizeQualityEvaluationRun({ data: { id: "legacy-run", runtimeId: "openclaw" } }).runtimeEnvironment, null);
});

test("optimization threshold normalization keeps bounded defaults", () => {
  assert.deepEqual(normalizeOptimizationSuggestionThresholds(null), {
    minSuccessRatePercent: 95, maxP95Ms: 1000, minRuntimeSamples: 5,
  });
  assert.deepEqual(normalizeOptimizationSuggestionThresholds({ data: { minSuccessRatePercent: 97.5, maxP95Ms: 800, minRuntimeSamples: 10 } }), {
    minSuccessRatePercent: 97.5, maxP95Ms: 800, minRuntimeSamples: 10,
  });
});

test("detail quality normalization exposes empty states and comparison reasons", () => {
  assert.equal(normalizeSkillQualityDetail(null).latestSnapshot, null);
  assert.deepEqual(normalizeSkillQualityDetail(null).snapshotHistory, []);
  const detail = normalizeSkillQualityDetail({ data: { snapshotHistory: [{ snapshotId: "newer", score: 91 }, { snapshotId: "older", score: 88 }] } });
  assert.deepEqual(detail.snapshotHistory.map((item) => item.snapshotId), ["newer", "older"]);
  assert.equal(normalizeSkillQualityDetail(null).runtime.totals.total, 0);
  assert.equal(normalizeQualityComparison(null).comparable, false);
  assert.equal(normalizeQualityComparison({ data: { comparable: false, reasonCode: "NO_COMPARABLE_SNAPSHOT" } }).reasonCode, "NO_COMPARABLE_SNAPSHOT");
});

test("benchmark normalization exposes conclusion and safe comparison evidence", () => {
  const result = normalizeQualityBenchmarks({ data: [{ benchmarkId: "b-1", skillId: "skill-a", baselineVersion: "1.0.0", candidateVersion: "1.1.0", conclusion: "IMPROVED", runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway", comparison: { comparable: true, delta: { score: 4, p95Ms: -20 }, candidate: { runtimeId: "openclaw", runtimeEnvironment: { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE" } } } }] });
  assert.equal(result[0].id, "b-1");
  assert.equal(result[0].conclusion, "IMPROVED");
  assert.equal(result[0].runtimeId, "openclaw");
  assert.equal(result[0].mcpServerId, "mcp-network");
  assert.equal(result[0].llmProviderId, "llm-gateway");
  assert.equal(result[0].comparison.delta.score, 4);
  assert.equal(result[0].comparison.candidate.runtimeEnvironment.version, "runtime-v2");
  assert.deepEqual(normalizeQualityBenchmarks(null), []);
});

test("quality suggestion normalization keeps safe evidence and actions", () => {
  assert.deepEqual(normalizeQualitySuggestions({ data: [{ category: "LATENCY", severity: "MEDIUM", evidence: ["p95Ms=1200"], recommendedAction: "检查依赖" }] }), [{
    id: "suggestion-0", severity: "MEDIUM", category: "LATENCY", title: "LATENCY", evidence: ["p95Ms=1200"], recommendedAction: "检查依赖",
    dispositionStatus: "OPEN", dispositionNote: "", dispositionBy: "", dispositionAt: "", dispositionEvidenceStatus: "NOT_LINKED",
  }]);
  assert.deepEqual(normalizeQualitySuggestions(null), []);
});

test("operations metrics normalization supplies safe empty states", () => {
  assert.deepEqual(normalizeOperationsMetrics(null), {
    window: "15m",
    generatedAt: "",
    health: { status: "UNKNOWN", packageStorage: "UNKNOWN", invocationPersistence: "UNKNOWN", metricsPersistence: "UNKNOWN" },
    requests: { total: 0, successes: 0, clientErrors: 0, serverErrors: 0 },
    latency: { p50Ms: 0, p95Ms: 0, maxMs: 0 },
    securityEvents: [],
  });
  assert.deepEqual(normalizeOperationsMetrics({
    window: "5m",
    health: { status: "UP", packageStorage: "CONFIGURED", invocationPersistence: "ENABLED", metricsPersistence: "ENABLED" },
    requests: { total: 2, successes: 2, clientErrors: 0, serverErrors: 0 },
    latency: { p50Ms: 10, p95Ms: 49, maxMs: 60 },
    securityEvents: { RATE_LIMITED: 1 },
}).securityEvents, [{ code: "RATE_LIMITED", count: 1 }]);
});

test("operations alert normalization supplies safe defaults and numeric values", () => {
  assert.deepEqual(normalizeOperationsAlerts(null), { alerts: [] });
  assert.deepEqual(normalizeOperationsAlerts({ data: [{ rule: "P95_LATENCY", status: "ACTIVE", currentValue: "1200", threshold: 1000 }] }), {
    alerts: [{ rule: "P95_LATENCY", eventCode: null, status: "ACTIVE", currentValue: 1200, threshold: 1000, unit: "", firstTriggeredAt: "", lastEvaluatedAt: "" }],
  });
});

test("runtime operations normalization keeps source separation and safe empty states", () => {
  assert.deepEqual(normalizeRuntimeOperations(null), {
    window: "24h",
    generatedAt: "",
    dataSource: "all",
    filters: { skillId: "", version: "", teamId: "" },
    totals: { total: 0, successes: 0, failures: 0, timeouts: 0, cancellations: 0, successRate: 0 },
    latency: { sampleCount: 0, p50Ms: 0, p95Ms: 0, maxMs: 0 },
    errors: [],
    versionAdoption: [],
    sources: [],
    trend: [],
  });
  const normalized = normalizeRuntimeOperations({ data: {
    dataSource: "all",
    totals: { total: 4, successes: 2, failures: 1, timeouts: 1, cancellations: 0, successRate: 50 },
    sources: [{ dataSource: "mock", total: 2, successes: 1, p95Ms: 80 }],
  }});
  assert.equal(normalized.totals.successRate, 50);
  assert.equal(normalized.sources[0].dataSource, "mock");
});

test("trace normalization keeps only safe metadata and stable defaults", () => {
  const result = normalizeTraceObservations({ data: [{ traceId: "trace-1", spanId: "span-1", skillId: "skill-a", version: "1.0.0", operation: "skill.run", status: "failure", durationMs: "90", errorCode: "DEPENDENCY_TIMEOUT", dataSource: "production", occurredAt: "2026-08-21T00:00:00Z", prompt: "must not be shown" }] });
  assert.deepEqual(result[0], { traceId: "trace-1", spanId: "span-1", skillId: "skill-a", version: "1.0.0", operation: "skill.run", status: "failure", durationMs: 90, errorCode: "DEPENDENCY_TIMEOUT", dataSource: "production", occurredAt: "2026-08-21T00:00:00Z" });
  assert.deepEqual(normalizeTraceObservations(null), []);
});
