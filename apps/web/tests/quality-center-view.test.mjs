import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";
import { createServer } from "vite";

const webRoot = fileURLToPath(new URL("..", import.meta.url));
let vite;
let QualityCenterView;
let React;
let act;
let createRoot;
let dom;

async function waitFor(check, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      check();
      return;
    } catch {
      await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    }
  }
  check();
}

before(async () => {
  dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://127.0.0.1:5173/#/quality" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: dom.window.navigator });
  globalThis.HTMLElement = dom.window.HTMLElement;
  globalThis.Event = dom.window.Event;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  ({ default: React, act } = await import("react"));
  ({ createRoot } = await import("react-dom/client"));
  vite = await createServer({ root: webRoot, server: { middlewareMode: true }, appType: "custom", logLevel: "silent" });
  ({ QualityCenterView } = await vite.ssrLoadModule("/src/QualityCenterView.jsx"));
});

after(async () => {
  await vite?.close();
  dom?.window.close();
});

test("quality center shows reserved provider contracts separately from active mock providers", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const suggestionDispositions = [];
  const providerProbes = [];
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: [] }),
    getQualityRules: async () => ({ data: {} }),
    getOptimizationSuggestionThresholds: async () => ({ data: { minSuccessRatePercent: 95, maxP95Ms: 1000, minRuntimeSamples: 5 } }),
    updateOptimizationSuggestionThresholds: async (input) => ({ data: input }),
    listQualityProviderContracts: async () => ({ data: [
      { id: "openclaw-runner", kind: "runner", status: "CONTRACT_ONLY", version: "contract-v1", capabilities: ["execute", "timeout"], configReference: "secret://<provider>" },
    ] }),
    getQualityProviderContractVerification: async () => ({ data: [
      { providerId: "openclaw-runner", kind: "runner", expectedVersion: "contract-v1", actualVersion: "contract-v1", activeStatus: "CONTRACT_ONLY", verificationStatus: "MATCHED", reason: "PROVIDER_CONTRACT_MATCHED_NOT_ENABLED", missingCapabilities: [], unexpectedCapabilities: [] },
    ] }),
    listQualityProviders: async () => ({ data: [
      { id: "mock-runner", kind: "runner", status: "UP", version: "1.0", capabilities: ["execute"], healthReason: "" },
    ] }),
    getQualityProviderReadiness: async () => ({ data: {
      status: "PARTIAL", activeProviderCount: 3, healthyProviderCount: 3,
      contractOnlyProviderCount: 3, notConfiguredProviderCount: 0,
      contractOnlyProviderIds: ["deepeval-evaluation", "langfuse-observability", "openclaw-runner"],
      notConfiguredProviderIds: ["openclaw-runner"],
      reason: "EXTERNAL_PROVIDERS_CONTRACT_ONLY",
    } }),
    getLatestQualityProviderProbes: async () => ({ data: [
      { providerId: "openclaw-runner", kind: "runner", status: "STALE", reason: "PROBE_EXPIRED", latencyMs: 17 },
    ] }),
    getPackageSecurityReadiness: async () => ({ data: {
      mode: "REQUIRED", status: "DEGRADED", scannerId: "external-package-security", scannerVersion: "contract-v1",
      reasonCode: "EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE",
      capabilities: ["MALWARE"], missingCapabilities: ["DEPENDENCY_VULNERABILITY", "LICENSE"],
    } }),
    probeQualityProviders: async () => {
      providerProbes.push(true);
      return { data: [
        { providerId: "openclaw-runner", kind: "runner", status: "REACHABLE", reason: "PROBE_OK", httpStatus: 204, latencyMs: 17 },
      ] };
    },
    getSkillQualitySuggestions: async () => ({ data: [
      { id: "runtime-latency", title: "运行 P95 延迟偏高", severity: "MEDIUM", recommendedAction: "检查外部依赖", evidence: ["p95Ms=1200"], dispositionStatus: "OPEN" },
    ] }),
    updateSkillQualitySuggestionDisposition: async (skillId, suggestionId, input) => { suggestionDispositions.push({ skillId, suggestionId, input }); return { data: { id: suggestionId, title: "运行 P95 延迟偏高", severity: "MEDIUM", recommendedAction: "检查外部依赖", evidence: ["p95Ms=1200"], dispositionStatus: input.status } }; },
  };

  await act(async () => root.render(React.createElement(QualityCenterView, {
    api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0",
  })));
  try {
    await waitFor(() => assert.equal(document.querySelector("[data-testid=provider-contracts]")?.textContent.includes("openclaw-runner"), true));
    assert.equal(document.querySelector("[data-testid=provider-contracts]").textContent.includes("CONTRACT_ONLY"), true);
    assert.equal(document.querySelector("[data-testid=provider-contracts]").textContent.includes("secret://<provider>"), true);
    assert.match(document.querySelector("[data-testid=provider-contract-verification]")?.textContent || "", /PROVIDER_CONTRACT_MATCHED_NOT_ENABLED/);
    assert.match(document.querySelector("[data-testid=provider-contract-verification]")?.textContent || "", /CONTRACT_ONLY/);
    assert.equal(document.querySelector("[data-testid=active-providers]")?.textContent.includes("mock-runner"), true);
    assert.equal(document.querySelector("[data-testid=active-providers]")?.textContent.includes("UP"), true);
    assert.equal(document.querySelector("[data-testid=provider-readiness]")?.textContent.includes("PARTIAL"), true);
    assert.equal(document.querySelector("[data-testid=provider-readiness]")?.textContent.includes("3 个外部契约"), true);
    assert.equal(document.querySelector("[data-testid=provider-readiness]")?.textContent.includes("外部 Provider 尚未启用真实适配器"), true);
    assert.equal(document.querySelector("[data-testid=provider-readiness]")?.textContent.includes("待接入 Provider：deepeval-evaluation、langfuse-observability、openclaw-runner"), true);
    assert.equal(document.querySelector("[data-testid=provider-readiness]")?.textContent.includes("配置缺失 Provider：openclaw-runner"), true);
    assert.match(document.querySelector("[data-testid=package-security-readiness]")?.textContent || "", /EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE/);
    assert.match(document.querySelector("[data-testid=package-security-readiness]")?.textContent || "", /DEPENDENCY_VULNERABILITY、LICENSE/);
    assert.match(document.querySelector("[data-testid=provider-probe-results]")?.textContent || "", /STALE/);
    assert.match(document.querySelector("[data-testid=provider-probe-results]")?.textContent || "", /PROBE_EXPIRED/);
    await act(async () => document.querySelector("[data-testid=provider-connectivity-probe]").click());
    await waitFor(() => assert.equal(providerProbes.length, 1));
    assert.match(document.querySelector("[data-testid=provider-probe-results]")?.textContent || "", /REACHABLE/);
    assert.match(document.querySelector("[data-testid=provider-probe-results]")?.textContent || "", /PROBE_OK/);
    assert.doesNotMatch(document.querySelector("[data-testid=provider-probe-results]")?.textContent || "", /secret:\/\//);
    assert.equal(document.querySelector(".quality-submit-panel input")?.value, "demo-skill");
    assert.equal(document.querySelectorAll(".quality-submit-panel input")[1]?.value, "2.0.0");
    assert.match(document.querySelector("[data-testid=quality-suggestions]")?.textContent || "", /运行 P95 延迟偏高/);
    assert.match(document.querySelector("[data-testid=quality-suggestions]")?.textContent || "", /p95Ms=1200/);
    const dispositionButton = [...document.querySelectorAll("[data-testid=quality-suggestion-actions] button")].find((button) => button.textContent === "已确认");
    await act(async () => dispositionButton.click());
    await waitFor(() => assert.equal(suggestionDispositions.length, 1));
    assert.equal(suggestionDispositions[0].skillId, "demo-skill");
    assert.equal(suggestionDispositions[0].input.status, "ACKNOWLEDGED");
    assert.equal(suggestionDispositions[0].input.dataSource, "mock");
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center can start and inspect a compatibility matrix", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const created = [];
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: [{ id: "smoke", name: "Smoke", version: "smoke-v1", enabled: true }] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    listQualityCompatibilityMatrices: async () => ({ data: [] }),
    createQualityCompatibilityMatrix: async (input) => {
      created.push(input);
      return { data: { matrixRunId: "matrix-1", skillId: input.skillId, skillVersion: input.skillVersion, status: "QUEUED", totalCases: 1, completedCases: 0, releaseGateRequired: input.releaseGateRequired } };
    },
    getQualityCompatibilityMatrix: async () => ({ data: { matrixRunId: "matrix-1", skillId: "demo-skill", skillVersion: "2.0.0", status: "COMPLETED", totalCases: 1, completedCases: 1, releaseGateRequired: false } }),
    getQualityCompatibilityMatrixCases: async () => ({ data: [{ caseId: "case-1", runtimeId: "runtime-prod", status: "COMPLETED", gateStatus: "PASSED", score: 100 }] }),
    listExecutionEnvironments: async () => ({ data: [
      { environmentId: "runtime-prod", kind: "AGENT_RUNTIME", version: "runtime-v1", status: "ACTIVE" },
      { environmentId: "runtime-canary", kind: "AGENT_RUNTIME", version: "runtime-v2", status: "ACTIVE" },
    ] }),
  };
  await act(async () => root.render(React.createElement(QualityCenterView, {
    api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0", initialEnvironmentFilters: { runtimeId: "runtime-prod" },
  })));
  try {
    await waitFor(() => assert.ok(document.querySelector("[data-testid=compatibility-matrix-panel]")));
    const runtimeSelect = document.querySelector("[aria-label='Matrix Runtime IDs']");
    assert.ok(runtimeSelect?.multiple);
    runtimeSelect.options[0].selected = true;
    runtimeSelect.options[1].selected = true;
    await act(async () => runtimeSelect.dispatchEvent(new Event("change", { bubbles: true })));
    await act(async () => document.querySelector("[data-testid=compatibility-matrix-panel] form button").click());
    await waitFor(() => assert.equal(created.length, 1));
    assert.deepEqual(created[0].runtimeIds, ["runtime-prod", "runtime-canary"]);
    assert.equal(created[0].policy, "ALL_MUST_PASS");
    await waitFor(() => assert.match(document.querySelector("[data-testid=compatibility-matrix-panel]")?.textContent || "", /runtime-prod/));
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center pins the active suite version and copies an immutable version", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const submissions = [];
  const created = [];
  const suites = [
    { id: "release", name: "Release", version: "release-v1", enabled: false, cases: [{ id: "case-1", name: "成功路径" }] },
    { id: "release", name: "Release", version: "release-v2", enabled: true, cases: [{ id: "case-1", name: "成功路径" }] },
  ];
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: suites }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    submitQualityEvaluation: async (input) => { submissions.push(input); return { data: { id: "run-1", ...input, status: "QUEUED" } }; },
    createQualitySuite: async (input) => { created.push(input); return { data: input }; },
  };
  await act(async () => root.render(React.createElement(QualityCenterView, {
    api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0",
  })));
  try {
    await waitFor(() => assert.match(document.querySelector("[data-testid=quality-suites]")?.textContent || "", /release-v2/));
    await act(async () => document.querySelector(".quality-submit-panel form button").click());
    await waitFor(() => assert.equal(submissions.length, 1));
    assert.equal(submissions[0].suiteId, "release");
    assert.equal(submissions[0].suiteVersion, "release-v2");

    const copyButton = [...document.querySelectorAll("[data-testid=suite-copy]")]
      .find((button) => button.parentElement?.textContent.includes("release-v2"));
    await act(async () => copyButton.click());
    assert.equal(document.querySelector("[aria-label='新套件 ID']")?.value, "release");
    assert.equal(document.querySelector("[aria-label='新套件版本']")?.value, "release-v3");
    await act(async () => document.querySelector(".quality-suite-form button").click());
    await waitFor(() => assert.equal(created.length, 1));
    assert.equal(created[0].version, "release-v3");
    assert.deepEqual(created[0].cases, [{ id: "case-1", name: "成功路径" }]);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center does not poll a missing evaluation status endpoint", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const runtimeErrors = [];
  const onWindowError = (event) => {
    runtimeErrors.push(event.error || event.message);
    event.preventDefault();
  };
  window.addEventListener("error", onWindowError);
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: [{ id: "smoke", name: "Smoke", version: "smoke-v1", enabled: true }] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    submitQualityEvaluation: async () => ({ data: {
      id: "run-compat",
      skillId: "demo-skill",
      skillVersion: "2.0.0",
      suiteId: "smoke",
      suiteVersion: "smoke-v1",
      status: "QUEUED",
      totalCases: 1,
      completedCases: 0,
    } }),
  };

  await act(async () => root.render(React.createElement(QualityCenterView, {
    api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0",
  })));
  try {
    await waitFor(() => assert.ok(document.querySelector(".quality-submit-panel")));
    await act(async () => document.querySelector(".quality-submit-panel form button").click());
    await waitFor(() => assert.match(document.querySelector(".quality-run-panel")?.textContent || "", /最近任务/));
    await new Promise((resolve) => setTimeout(resolve, 350));
    assert.equal(runtimeErrors.length, 0);
  } finally {
    window.removeEventListener("error", onWindowError);
    await act(async () => root.unmount());
  }
});

test("quality center can cancel a running compatibility matrix", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const cancellations = [];
  const runningMatrix = { matrixRunId: "matrix-running", skillId: "demo-skill", skillVersion: "2.0.0", status: "RUNNING", totalCases: 1, completedCases: 0, releaseGateRequired: false };
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: [{ id: "smoke", name: "Smoke", version: "smoke-v1", enabled: true }] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    listQualityCompatibilityMatrices: async () => ({ data: [] }),
    createQualityCompatibilityMatrix: async () => ({ data: runningMatrix }),
    getQualityCompatibilityMatrix: async () => ({ data: runningMatrix }),
    getQualityCompatibilityMatrixCases: async () => ({ data: [] }),
    cancelQualityCompatibilityMatrix: async (matrixRunId) => {
      cancellations.push(matrixRunId);
      return { data: { ...runningMatrix, status: "CANCELLED", completedCases: 1 } };
    },
    listExecutionEnvironments: async () => ({ data: [
      { environmentId: "runtime-prod", kind: "AGENT_RUNTIME", version: "runtime-v1", status: "ACTIVE" },
    ] }),
  };
  await act(async () => root.render(React.createElement(QualityCenterView, {
    api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0", initialEnvironmentFilters: { runtimeId: "runtime-prod" },
  })));
  try {
    await waitFor(() => assert.ok(document.querySelector("[data-testid=compatibility-matrix-panel]")));
    const createButton = [...document.querySelectorAll("[data-testid=compatibility-matrix-panel] button")].find((button) => button.textContent.includes("运行兼容性矩阵"));
    await act(async () => createButton.click());
    await waitFor(() => assert.ok([...document.querySelectorAll("[data-testid=compatibility-matrix-panel] button")].some((button) => button.textContent.includes("取消矩阵"))));
    const cancelButton = [...document.querySelectorAll("[data-testid=compatibility-matrix-panel] button")].find((button) => button.textContent.includes("取消矩阵"));
    await act(async () => cancelButton.click());
    await waitFor(() => assert.deepEqual(cancellations, ["matrix-running"]));
    assert.match(document.querySelector("[data-testid=compatibility-matrix-panel]")?.textContent || "", /已取消/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center uses the managed execution environment catalog for new context", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const evaluationQueries = [];
  const api = {
    listQualityEvaluations: async (skillId, params) => { evaluationQueries.push({ skillId, params }); return { data: [] }; },
    listQualitySuites: async () => ({ data: [] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    listExecutionEnvironments: async () => ({ data: [
      { environmentId: "runtime-prod", kind: "AGENT_RUNTIME", version: "runtime-v2", status: "ACTIVE", capabilities: ["execute"] },
      { environmentId: "mcp-prod", kind: "MCP_SERVER", version: "mcp-v1", status: "ACTIVE", capabilities: ["tools"] },
      { environmentId: "llm-prod", kind: "LLM_PROVIDER", version: "llm-v3", status: "DEGRADED", capabilities: ["chat"] },
    ] }),
  };
  await act(async () => root.render(React.createElement(QualityCenterView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.equal(document.querySelector("[data-testid=execution-environment-catalog]")?.textContent.includes("runtime-prod"), true));
    assert.ok(document.querySelector(".quality-environment-form select[aria-label='Runtime ID']"));
    assert.ok(document.querySelector(".quality-environment-form select[aria-label='MCP Server ID']"));
    assert.equal(document.querySelector(".quality-environment-form select[aria-label='LLM Provider ID'] option[value='llm-prod']")?.disabled, true);
    const runtimeSelect = document.querySelector(".quality-environment-form select[aria-label='Runtime ID']");
    await act(async () => {
      runtimeSelect.value = "runtime-prod";
      runtimeSelect.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await waitFor(() => assert.equal(evaluationQueries.at(-1)?.params.runtimeId, "runtime-prod"));
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center allows admins to update optimization thresholds", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const updates = [];
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: [] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    getOptimizationSuggestionThresholds: async () => ({ data: { minSuccessRatePercent: 95, maxP95Ms: 1000, minRuntimeSamples: 5 } }),
    updateOptimizationSuggestionThresholds: async (input) => { updates.push(input); return { data: input }; },
  };
  await act(async () => root.render(React.createElement(QualityCenterView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.equal(document.querySelector(".quality-thresholds-panel")?.textContent.includes("优化建议阈值"), true));
    await act(async () => document.querySelector(".quality-threshold-form button").click());
    await waitFor(() => assert.equal(updates.length, 1));
    assert.equal(updates[0].minSuccessRatePercent, 95);
    assert.equal(updates[0].maxP95Ms, 1000);
    assert.equal(updates[0].minRuntimeSamples, 5);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center turns an optimization suggestion into a trackable work item", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const created = [];
  const transitions = [];
  const evidenceBindings = [];
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: [{ id: "smoke", name: "Smoke", version: "smoke-v1", enabled: true }] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    getOptimizationSuggestionThresholds: async () => ({ data: null }),
    getSkillQualitySuggestions: async () => ({ data: [
      { id: "runtime-data", title: "运行 P95 延迟偏高", severity: "MEDIUM", category: "RUNTIME", recommendedAction: "检查外部依赖", evidence: ["p95Ms=1200"], dispositionStatus: "OPEN" },
    ] }),
    listOptimizationWorkItems: async () => ({ data: [] }),
    createOptimizationWorkItem: async (input) => {
      created.push(input);
      return { data: { workItemId: "work-1", suggestionId: input.suggestionId, suggestionTitle: "运行 P95 延迟偏高", status: "OPEN", hypothesis: input.hypothesis, suiteId: input.suiteId, suiteVersion: input.suiteVersion } };
    },
    updateOptimizationWorkItemStatus: async (workItemId, input) => {
      transitions.push({ workItemId, input });
      return { data: { workItemId, suggestionId: "runtime-data", suggestionTitle: "运行 P95 延迟偏高", status: input.status, candidateVersion: input.candidateVersion || "2.0.0", evidenceType: "BENCHMARK", evidenceId: "benchmark-1", outcome: input.outcome || "延迟改善已验证", hypothesis: "降低 P95" } };
    },
    bindOptimizationWorkItemEvidence: async (workItemId, input) => {
      evidenceBindings.push({ workItemId, input });
      return { data: { workItemId, suggestionId: "runtime-data", suggestionTitle: "运行 P95 延迟偏高", status: "READY_FOR_EVALUATION", candidateVersion: "2.0.0", evidenceType: input.evidenceType, evidenceId: input.evidenceId, outcome: input.outcome, hypothesis: "降低 P95" } };
    },
  };
  await act(async () => root.render(React.createElement(QualityCenterView, { api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0" })));
  try {
    await waitFor(() => assert.equal(document.querySelector("[data-testid=optimization-work-items]")?.textContent.includes("运行 P95 延迟偏高"), true));
    const hypothesis = document.querySelector("[data-testid=optimization-work-item-form] input");
    const setNativeValue = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
    await act(async () => {
      setNativeValue.call(hypothesis, "降低 P95");
      hypothesis.dispatchEvent(new Event("input", { bubbles: true }));
      document.querySelector("[data-testid=optimization-work-item-form] button").click();
    });
    await waitFor(() => assert.equal(created.length, 1));
    assert.deepEqual(created[0], {
      skillId: "demo-skill", sourceVersion: "2.0.0", suggestionId: "runtime-data", hypothesis: "降低 P95",
      ownerId: "admin", dataSource: "mock", runtimeId: "", mcpServerId: "", llmProviderId: "",
      suiteId: "smoke", suiteVersion: "smoke-v1",
    });
    assert.match(document.querySelector("[data-testid=optimization-work-items]")?.textContent || "", /套件 smoke · 版本 smoke-v1/);
    await act(async () => document.querySelector("[data-testid=optimization-work-item-status]").click());
    await waitFor(() => assert.equal(transitions.length, 1));
    assert.deepEqual(transitions[0], { workItemId: "work-1", input: { status: "PLANNED" } });
    await act(async () => document.querySelector("[data-testid=optimization-work-item-status]").click());
    await waitFor(() => assert.equal(transitions.length, 2));
    await act(async () => document.querySelector("[data-testid=optimization-work-item-status]").click());
    await waitFor(() => assert.equal(transitions.length, 3));
    assert.deepEqual(transitions[2], { workItemId: "work-1", input: { status: "READY_FOR_EVALUATION", candidateVersion: "2.0.0" } });
    const evidenceId = document.querySelector("[data-testid=optimization-work-item-evidence-id]");
    await act(async () => {
      setNativeValue.call(evidenceId, "benchmark-1");
      evidenceId.dispatchEvent(new Event("input", { bubbles: true }));
      const outcome = document.querySelector("[data-testid=optimization-work-item-evidence-outcome]");
      setNativeValue.call(outcome, "延迟改善已验证");
      outcome.dispatchEvent(new Event("input", { bubbles: true }));
      document.querySelector("[data-testid=optimization-work-item-evidence]").click();
    });
    await waitFor(() => assert.equal(evidenceBindings.length, 1));
    assert.deepEqual(evidenceBindings[0], { workItemId: "work-1", input: { evidenceType: "BENCHMARK", evidenceId: "benchmark-1", outcome: "延迟改善已验证" } });
    assert.match(document.querySelector("[data-testid=optimization-work-items]")?.textContent || "", /READY_FOR_EVALUATION/);
    await act(async () => document.querySelector("[data-testid=optimization-work-item-complete]").click());
    await waitFor(() => assert.equal(transitions.length, 4));
    assert.deepEqual(transitions[3], { workItemId: "work-1", input: { status: "COMPLETED", outcome: "延迟改善已验证" } });
    assert.match(document.querySelector("[data-testid=optimization-work-items]")?.textContent || "", /COMPLETED/);
    assert.match(document.querySelector("[data-testid=optimization-work-item-evidence-ledger]")?.textContent || "", /BENCHMARK · benchmark-1/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center orchestrates an optimization experiment without completing the work item", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const calls = [];
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: [{ id: "smoke", name: "Smoke", version: "smoke-v1", enabled: true }] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    getOptimizationSuggestionThresholds: async () => ({ data: null }),
    getSkillQualitySuggestions: async () => ({ data: [] }),
    listOptimizationWorkItems: async () => ({ data: [{ workItemId: "work-1", suggestionTitle: "运行 P95 延迟偏高", status: "READY_FOR_EVALUATION", candidateVersion: "2.0.0", suiteId: "smoke", suiteVersion: "smoke-v1", evidenceType: "NONE", evidenceId: "" }] }),
    listOptimizationExperiments: async () => ({ data: [] }),
    createOptimizationExperiment: async (input) => { calls.push({ method: "create", input }); return { data: { experimentId: "experiment-1", workItemId: input.workItemId, status: "RUNNING", evaluationRunId: "run-1" } }; },
    reconcileOptimizationExperiment: async (experimentId) => { calls.push({ method: "reconcile", experimentId }); return { data: { experimentId, workItemId: "work-1", status: "COMPLETED", evaluationRunId: "run-1", qualitySnapshotId: "run-1" } }; },
    benchmarkOptimizationExperiment: async (experimentId, input) => { calls.push({ method: "benchmark", experimentId, input }); return { data: { experimentId, workItemId: "work-1", status: "COMPLETED", benchmarkId: "benchmark-1" } }; },
    decideOptimizationExperiment: async (experimentId) => { calls.push({ method: "decision", experimentId }); return { data: { experimentId, workItemId: "work-1", status: "COMPLETED", benchmarkId: "benchmark-1", decision: { decision: "PROMOTE_CANDIDATE", reasonCode: "BENCHMARK_IMPROVED", recommendedAction: "提交人工发布审核" } } }; },
  };
  await act(async () => root.render(React.createElement(QualityCenterView, { api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0" })));
  try {
    await waitFor(() => assert.equal(document.querySelector("[data-testid=optimization-work-items]")?.textContent.includes("READY_FOR_EVALUATION"), true));
    await act(async () => document.querySelector("[data-testid=optimization-experiment-start]").click());
    await waitFor(() => assert.deepEqual(calls[0], { method: "create", input: { workItemId: "work-1" } }));
    assert.match(document.querySelector("[data-testid=optimization-experiments]")?.textContent || "", /RUNNING/);
    await act(async () => document.querySelector("[data-testid=optimization-experiment-reconcile]").click());
    await waitFor(() => assert.equal(calls[1].method, "reconcile"));
    assert.match(document.querySelector("[data-testid=optimization-experiments]")?.textContent || "", /COMPLETED/);
    assert.match(document.querySelector("[data-testid=optimization-experiments]")?.textContent || "", /先生成 Benchmark/);
    assert.match(document.querySelector("[data-testid=optimization-work-items]")?.textContent || "", /READY_FOR_EVALUATION/);
    await act(async () => document.querySelector("[data-testid=optimization-experiment-benchmark]").click());
    await waitFor(() => assert.equal(calls[2].method, "benchmark"));
    await act(async () => document.querySelector("[data-testid=optimization-experiment-decision]").click());
    await waitFor(() => assert.equal(calls[3].method, "decision"));
    assert.match(document.querySelector("[data-testid=optimization-experiments]")?.textContent || "", /PROMOTE_CANDIDATE/);
    assert.match(document.querySelector("[data-testid=optimization-experiments]")?.textContent || "", /提交人工发布审核/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center captures post-release observations for promoted experiments", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const captures = [];
  const experiment = { experimentId: "experiment-1", workItemId: "work-1", skillId: "demo-skill", candidateVersion: "2.0.0", dataSource: "production", status: "COMPLETED", decision: { decision: "PROMOTE_CANDIDATE" } };
  const api = {
    listQualityEvaluations: async () => ({ data: [] }), listQualitySuites: async () => ({ data: [] }),
    getQualityRules: async () => ({ data: {} }), listQualityProviderContracts: async () => ({ data: [] }),
    getOptimizationSuggestionThresholds: async () => ({ data: null }), getSkillQualitySuggestions: async () => ({ data: [] }),
    listOptimizationWorkItems: async () => ({ data: [{ workItemId: "work-1", status: "READY_FOR_EVALUATION" }] }),
    listOptimizationExperiments: async () => ({ data: [experiment] }),
    listOptimizationExperimentObservations: async () => ({ data: [] }),
    captureOptimizationExperimentObservation: async (experimentId, input) => { captures.push({ experimentId, input }); return { data: { observationId: "observation-1", observationStatus: "CAPTURED", totalCalls: 4, successRate: 75, p95Ms: 120 } }; },
  };
  await act(async () => root.render(React.createElement(QualityCenterView, { api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0" })));
  try {
    await waitFor(() => assert.equal(document.querySelector("[data-testid=optimization-observation-capture]") !== null, true));
    await act(async () => document.querySelector("[data-testid=optimization-observation-capture]").click());
    await waitFor(() => assert.deepEqual(captures, [{ experimentId: "experiment-1", input: { window: "24h" } }]));
    assert.match(document.querySelector("[data-testid=optimization-observations]")?.textContent || "", /CAPTURED/);
    assert.match(document.querySelector("[data-testid=optimization-observations]")?.textContent || "", /75%/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center records a manual post-release assessment action", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const calls = [];
  const experiment = { experimentId: "experiment-1", workItemId: "work-1", skillId: "demo-skill", sourceVersion: "1.0.0", candidateVersion: "2.0.0", dataSource: "production", status: "COMPLETED", decision: { decision: "PROMOTE_CANDIDATE" } };
  const observation = { observationId: "observation-1", experimentId: "experiment-1", observationStatus: "CAPTURED", totalCalls: 8, successRate: 100, p95Ms: 80 };
  const api = {
    listQualityEvaluations: async () => ({ data: [] }), listQualitySuites: async () => ({ data: [] }),
    getQualityRules: async () => ({ data: {} }), listQualityProviderContracts: async () => ({ data: [] }),
    getOptimizationSuggestionThresholds: async () => ({ data: null }), getSkillQualitySuggestions: async () => ({ data: [] }),
    listOptimizationWorkItems: async () => ({ data: [{ workItemId: "work-1", status: "READY_FOR_EVALUATION" }] }),
    listOptimizationExperiments: async () => ({ data: [experiment] }),
    listOptimizationExperimentObservations: async () => ({ data: [observation] }),
    listOptimizationExperimentAssessments: async () => ({ data: [] }),
    createOptimizationExperimentAssessment: async (experimentId, input) => { calls.push({ experimentId, input }); return { data: { assessmentId: "assessment-1", experimentId, conclusion: "HEALTHY", recommendedAction: "KEEP", action: input.action, candidate: { successRate: 100, p95Ms: 80 }, baseline: { successRate: 95, p95Ms: 100 } } }; },
  };
  await act(async () => root.render(React.createElement(QualityCenterView, { api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0" })));
  try {
    await waitFor(() => assert.equal(document.querySelector("[data-testid=optimization-assessment-keep]") !== null, true));
    await act(async () => document.querySelector("[data-testid=optimization-assessment-keep]").click());
    await waitFor(() => assert.deepEqual(calls, [{ experimentId: "experiment-1", input: { observationId: "observation-1", action: "KEEP", note: "" } }]));
    assert.match(document.querySelector("[data-testid=optimization-assessments]")?.textContent || "", /HEALTHY/);
    assert.match(document.querySelector("[data-testid=optimization-assessments]")?.textContent || "", /KEEP/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center refreshes the linked follow-up work item after explicit follow-up action", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const workItemReads = [];
  const experiment = { experimentId: "experiment-1", workItemId: "work-1", skillId: "demo-skill", sourceVersion: "1.0.0", candidateVersion: "2.0.0", dataSource: "production", status: "COMPLETED", decision: { decision: "PROMOTE_CANDIDATE" } };
  const observation = { observationId: "observation-1", experimentId: "experiment-1", observationStatus: "CAPTURED", totalCalls: 8, successRate: 70, p95Ms: 1_200 };
  const api = {
    listQualityEvaluations: async () => ({ data: [] }), listQualitySuites: async () => ({ data: [] }),
    getQualityRules: async () => ({ data: {} }), listQualityProviderContracts: async () => ({ data: [] }),
    getOptimizationSuggestionThresholds: async () => ({ data: null }), getSkillQualitySuggestions: async () => ({ data: [] }),
    listOptimizationWorkItems: async () => {
      workItemReads.push(true);
      return { data: workItemReads.length > 1
        ? [{ workItemId: "work-follow-up-assessment-1", status: "OPEN", sourceVersion: "2.0.0", suggestionTitle: "发布后评估后续优化", suggestionEvidence: ["postReleaseAssessmentId=assessment-1"] }]
        : [{ workItemId: "work-1", status: "READY_FOR_EVALUATION" }] };
    },
    listOptimizationExperiments: async () => ({ data: [experiment] }),
    listOptimizationExperimentObservations: async () => ({ data: [observation] }),
    listOptimizationExperimentAssessments: async () => ({ data: [] }),
    createOptimizationExperimentAssessment: async (experimentId, input) => ({ data: {
      assessmentId: "assessment-1", experimentId, conclusion: "REGRESSION", recommendedAction: "CREATE_FOLLOW_UP",
      action: input.action, candidate: { successRate: 70, p95Ms: 1_200 }, baseline: { successRate: 95, p95Ms: 100 },
    } }),
  };
  await act(async () => root.render(React.createElement(QualityCenterView, { api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0" })));
  try {
    await waitFor(() => assert.equal(document.querySelector("[data-testid=optimization-assessment-follow-up]") !== null, true));
    await act(async () => document.querySelector("[data-testid=optimization-assessment-follow-up]").click());
    await waitFor(() => assert.match(document.querySelector("[data-testid=optimization-work-items]")?.textContent || "", /work-follow-up-assessment-1/));
    assert.match(document.querySelector("[data-testid=optimization-work-items]")?.textContent || "", /postReleaseAssessmentId=assessment-1/);
    assert.equal(workItemReads.length > 1, true);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center can reopen an abandoned optimization work item", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const transitions = [];
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: [] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    getOptimizationSuggestionThresholds: async () => ({ data: null }),
    listOptimizationWorkItems: async () => ({ data: [{ workItemId: "work-abandoned", suggestionId: "runtime-data", suggestionTitle: "运行 P95 延迟偏高", status: "ABANDONED", sourceVersion: "2.0.0", ownerId: "admin", hypothesis: "降低 P95", outcome: "暂缓实施", suiteId: "", suiteVersion: "" }] }),
    updateOptimizationWorkItemStatus: async (workItemId, input) => {
      transitions.push({ workItemId, input });
      return { data: { workItemId, suggestionId: "runtime-data", suggestionTitle: "运行 P95 延迟偏高", status: input.status, sourceVersion: "2.0.0", ownerId: "admin", hypothesis: "降低 P95" } };
    },
  };
  await act(async () => root.render(React.createElement(QualityCenterView, { api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0" })));
  try {
    await waitFor(() => assert.equal(document.querySelector("[data-testid=optimization-work-item-reopen]") !== null, true));
    assert.match(document.querySelector("[data-testid=optimization-work-items]")?.textContent || "", /未锁定套件版本/);
    await act(async () => document.querySelector("[data-testid=optimization-work-item-reopen]").click());
    await waitFor(() => assert.equal(transitions.length, 1));
    assert.deepEqual(transitions[0], { workItemId: "work-abandoned", input: { status: "OPEN" } });
    assert.match(document.querySelector("[data-testid=optimization-work-items]")?.textContent || "", /OPEN/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center can run a benchmark and show its effect conclusion", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const runs = [];
  const benchmarkQueries = [];
  const evaluationQueries = [];
  const api = {
    listQualityEvaluations: async (skillId, params) => { evaluationQueries.push({ skillId, params }); return { data: [{ id: "run-1", skillId, skillVersion: "1.2.0", suiteVersion: "smoke-v1", status: "COMPLETED", score: 100, dataSource: "mock", runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway", runtimeEnvironment: { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE" } }] }; },
    listQualitySuites: async () => ({ data: [] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    getOptimizationSuggestionThresholds: async () => ({ data: null }),
    listQualityBenchmarks: async (skillId, params) => { benchmarkQueries.push({ skillId, params }); return { data: [] }; },
    createQualityBenchmark: async (input) => { runs.push(input); return { data: { benchmarkId: "b-1", ...input, conclusion: "IMPROVED", createdAt: "2026-08-21", comparison: { candidate: { runtimeId: "openclaw", runtimeEnvironment: { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE" } } } } }; },
  };
  await act(async () => root.render(React.createElement(QualityCenterView, {
    api, role: "admin", initialEnvironmentFilters: {
      runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway",
    },
  })));
  try {
    await waitFor(() => assert.equal(benchmarkQueries.length, 1));
    assert.equal(evaluationQueries.length, 1);
    assert.equal(evaluationQueries[0].skillId, "eox-query");
    assert.equal(evaluationQueries[0].params.dataSource, "mock");
    assert.equal(evaluationQueries[0].params.runtimeId, "openclaw");
    assert.equal(evaluationQueries[0].params.mcpServerId, "mcp-network");
    assert.equal(evaluationQueries[0].params.llmProviderId, "llm-gateway");
    assert.match(document.querySelector(".quality-history-list")?.textContent || "", /Runtime openclaw/);
    assert.match(document.querySelector(".quality-history-list")?.textContent || "", /MCP mcp-network/);
    assert.match(document.querySelector(".quality-history-list")?.textContent || "", /LLM llm-gateway/);
    assert.equal(benchmarkQueries[0].skillId, "eox-query");
    assert.equal(benchmarkQueries[0].params.dataSource, "mock");
    assert.equal(benchmarkQueries[0].params.runtimeId, "openclaw");
    assert.equal(benchmarkQueries[0].params.mcpServerId, "mcp-network");
    assert.equal(benchmarkQueries[0].params.llmProviderId, "llm-gateway");
    await act(async () => document.querySelector(".quality-benchmark-form button").click());
    await waitFor(() => assert.equal(runs.length, 1));
    assert.equal(runs[0].runtimeId, "openclaw");
    assert.equal(runs[0].mcpServerId, "mcp-network");
    assert.equal(runs[0].llmProviderId, "llm-gateway");
    const history = document.querySelector(".quality-benchmark-list")?.textContent || "";
    assert.match(history, /IMPROVED/);
    assert.match(history, /Runtime openclaw/);
    assert.match(history, /MCP mcp-network/);
    assert.match(history, /LLM llm-gateway/);
    assert.match(history, /runtime-v2/);
    assert.match(history, /rev 7/);
    assert.match(history, /ACTIVE/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center lets admins run a published skill and shows runner execution history", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const executions = [];
  const executionQueries = [];
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: [] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    getOptimizationSuggestionThresholds: async () => ({ data: null }),
    listSkillExecutions: async (skillId, params) => { executionQueries.push({ skillId, params }); return { data: params.runtimeId === "openclaw" ? executions : [] }; },
    executeSkill: async (input) => {
      const record = { executionId: "exec-1", ...input, status: "SUCCEEDED", dataSource: "mock", providerId: "mock-runner", durationMs: 42, runtimeEnvironment: { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE" } };
      executions.unshift(record);
      return { data: record };
    },
  };
  await act(async () => root.render(React.createElement(QualityCenterView, {
    api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0",
    initialEnvironmentFilters: { runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway" },
  })));
  try {
    await waitFor(() => assert.ok(document.querySelector("[data-testid=runner-execution-panel]")));
    assert.equal(executionQueries[0].skillId, "demo-skill");
    assert.equal(executionQueries[0].params.dataSource, "mock");
    assert.equal(executionQueries[0].params.runtimeId, "openclaw");
    assert.equal(executionQueries[0].params.mcpServerId, "mcp-network");
    assert.equal(executionQueries[0].params.llmProviderId, "llm-gateway");
    await act(async () => document.querySelector("[data-testid=runner-execution-panel] button").click());
    await waitFor(() => assert.equal(executions.length, 1));
    assert.equal(executions[0].skillId, "demo-skill");
    assert.equal(executions[0].runtimeId, "openclaw");
    assert.match(document.querySelector("[data-testid=runner-execution-panel]")?.textContent || "", /SUCCEEDED/);
    assert.match(document.querySelector("[data-testid=runner-execution-history]")?.textContent || "", /exec-1/);
    assert.match(document.querySelector("[data-testid=runner-execution-history]")?.textContent || "", /runtime-v2/);
    assert.match(document.querySelector("[data-testid=runner-execution-history]")?.textContent || "", /rev 7/);
    assert.match(document.querySelector("[data-testid=runner-execution-history]")?.textContent || "", /ACTIVE/);
    const runtimeInput = document.querySelector(".quality-environment-form input");
    await act(async () => {
      const setNativeValue = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
      setNativeValue.call(runtimeInput, "other-runtime");
      runtimeInput.dispatchEvent(new Event("input", { bubbles: true }));
      runtimeInput.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await waitFor(() => assert.equal(document.querySelector("[data-testid=runner-execution-history]"), null));
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality center renders controlled release actions without writing during load", async () => {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const calls = [];
  const api = {
    listQualityEvaluations: async () => ({ data: [] }),
    listQualitySuites: async () => ({ data: [] }),
    getQualityRules: async () => ({ data: {} }),
    listQualityProviderContracts: async () => ({ data: [] }),
    listReleases: async () => ({ data: [{ releaseId: "release-1", version: "2.0.0", targetEnvironment: "STAGING", status: "REQUESTED", sha256: "abcdef1234567890", gateSnapshot: { outcome: "PASSED", reasonCodes: [] } }] }),
    getReleaseAdmission: async () => ({ data: { allowed: false, mode: "CONTROLLED", reasonCode: "RELEASE_ADMISSION_REQUIRED", releaseId: "" } }),
    createRelease: async (input) => { calls.push({ type: "create", input }); return { data: {} }; },
    approveRelease: async (releaseId) => { calls.push({ type: "approve", releaseId }); return { data: {} }; },
  };
  await act(async () => root.render(React.createElement(QualityCenterView, {
    api, role: "admin", initialSkillId: "demo-skill", initialSkillVersion: "2.0.0",
  })));
  try {
    await waitFor(() => assert.ok(document.querySelector("[data-testid=release-control-panel]")));
    assert.match(document.querySelector("[data-testid=release-control-panel]")?.textContent || "", /待审批/);
    assert.match(document.querySelector("[data-testid=release-admission-summary]")?.textContent || "", /CONTROLLED/);
    assert.match(document.querySelector("[data-testid=release-admission-summary]")?.textContent || "", /阻断/);
    assert.equal(calls.length, 0);
    await act(async () => document.querySelector("[data-testid=release-control-panel] button:not(.primary-action)").click());
    await waitFor(() => assert.equal(calls[0]?.type, "approve"));
  } finally {
    await act(async () => root.unmount());
  }
});
