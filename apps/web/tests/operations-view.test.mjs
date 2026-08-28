import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { createServer } from "vite";

const webRoot = fileURLToPath(new URL("..", import.meta.url));
let vite;
let OperationsMetricsView;
let dom;

async function waitFor(check, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try { check(); return; } catch { await act(() => new Promise((resolve) => setTimeout(resolve, 20))); }
  }
  check();
}

before(async () => {
  dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://127.0.0.1:5173/#/operations" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: dom.window.navigator });
  globalThis.HTMLElement = dom.window.HTMLElement;
  globalThis.Event = dom.window.Event;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  vite = await createServer({ root: webRoot, server: { middlewareMode: true }, appType: "custom", logLevel: "silent" });
  ({ OperationsMetricsView } = await vite.ssrLoadModule("/src/OperationsMetricsView.jsx"));
});

after(async () => {
  await vite?.close();
  dom?.window.close();
});

test("operations center shows redacted trace evidence for failed runs", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [] }),
    getSkillLifecycleProjectionReconciliation: async () => ({ data: { backend: "json", state: "LIVE_SOURCE", reasonCode: "", revision: 0, sourceCounts: {}, projectedCounts: {}, countDelta: {} } }),
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [{ traceId: "trace-1", spanId: "span-1", skillId: "skill-a", version: "1.0.0", operation: "skill.run", status: "failure", durationMs: 120, errorCode: "DEPENDENCY_TIMEOUT", dataSource: "production", occurredAt: "2026-08-21T00:00:00Z" }] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.ok(document.querySelector(".trace-failure-panel")));
    const panel = document.querySelector(".trace-failure-panel");
    assert.match(panel.textContent, /trace-1/);
    assert.match(panel.textContent, /skill\.run/);
    assert.match(panel.textContent, /DEPENDENCY_TIMEOUT/);
    assert.equal(panel.textContent.includes("prompt"), false);
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center shows lifecycle projection health and safe drift metadata", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [] }),
    getSkillLifecycleProjectionReconciliation: async () => ({ data: {
      backend: "postgresql",
      state: "DRIFTED",
      reasonCode: "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED",
      revision: 7,
      projectionAgeSeconds: 120,
      maxProjectionAgeSeconds: 900,
      sourceCounts: { skillCount: 3, versionCount: 5, releaseCount: 2, scopeCount: 2, relationCount: 1 },
      projectedCounts: { skillCount: 2, versionCount: 5, releaseCount: 2, scopeCount: 2, relationCount: 1 },
      countDelta: { skillCount: 1, versionCount: 0, releaseCount: 0, scopeCount: 0, relationCount: 0 },
    } }),
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.ok(document.querySelector('[data-testid="lifecycle-health"]')));
    const panel = document.querySelector('[data-testid="lifecycle-health"]');
    assert.match(panel.textContent, /DRIFTED/);
    assert.match(panel.textContent, /SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED/);
    assert.match(panel.textContent, /Revision7/);
    assert.match(panel.textContent, /技能\+1/);
    assert.equal(panel.textContent.includes("Prompt"), false);
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center shows the aggregated production handoff readiness", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  let probeCount = 0;
  let readinessCount = 0;
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [] }),
    getPlatformReadiness: async () => ({ data: readinessCount++ === 0 ? {
      overall: "NOT_READY",
      scope: "PRODUCTION_HANDOFF",
      components: [{ componentId: "PRODUCTION_EXTERNAL_EVIDENCE", status: "NOT_READY", reasonCode: "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED", summary: "生产外部依赖与上线证据尚未完成核验" }, { componentId: "RELEASE_TARGET", status: "NOT_READY", reasonCode: "RELEASE_TARGET_PROBE_REQUIRED", summary: "尚未形成发布目标连通性证据" }],
      blockingReasonCodes: ["PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED"],
    } : {
      overall: "NOT_READY",
      scope: "PRODUCTION_HANDOFF",
      components: [{ componentId: "PRODUCTION_EXTERNAL_EVIDENCE", status: "NOT_READY", reasonCode: "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED", summary: "生产外部依赖与上线证据尚未完成核验" }, { componentId: "RELEASE_TARGET", status: "READY", reasonCode: "RELEASE_TARGET_PROBE_OK", summary: "发布目标最近一次状态探测可用" }],
      blockingReasonCodes: ["PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED"],
    } }),
    probeReleaseTarget: async () => { probeCount += 1; return { data: { targetId: "release-target", status: "REACHABLE", reasonCode: "PROBE_OK", httpStatus: 204, latencyMs: 12 } }; },
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.ok(document.querySelector('[data-testid="platform-readiness"]')));
    const panel = document.querySelector('[data-testid="platform-readiness"]');
    assert.match(panel.textContent, /NOT_READY/);
    assert.match(panel.textContent, /PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED/);
    assert.equal(panel.textContent.includes("https://"), false);
    const probe = panel.querySelector("[data-testid=release-target-probe]");
    assert.ok(probe);
    await act(async () => probe.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true })));
    await waitFor(() => assert.equal(probeCount, 1));
    await waitFor(() => assert.match(panel.textContent, /PROBE_OK/));
    await waitFor(() => assert.match(panel.textContent, /RELEASE_TARGETREADY/));
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center shows release target probe history and failure count", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [] }),
    getPlatformReadiness: async () => ({ data: { overall: "NOT_READY", scope: "PRODUCTION_HANDOFF", components: [], blockingReasonCodes: [] } }),
    listReleaseTargetProbes: async () => ({ data: [
      { targetId: "release-target", status: "TIMEOUT", reasonCode: "PROBE_TIMEOUT", latencyMs: 1500, checkedAt: "2026-08-25T00:00:00Z" },
      { targetId: "release-target", status: "REACHABLE", reasonCode: "PROBE_OK", httpStatus: 204, latencyMs: 12, checkedAt: "2026-08-24T23:59:00Z" },
    ] }),
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.ok(document.querySelector("[data-testid=release-target-probe-history]")));
    const panel = document.querySelector("[data-testid=release-target-probe-history]");
    assert.match(panel.textContent, /TIMEOUT/);
    assert.match(panel.textContent, /PROBE_TIMEOUT/);
    assert.match(panel.textContent, /失败 1/);
    assert.equal(panel.textContent.includes("secret.example"), false);
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center exposes the external search index probe safely", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  let probeCount = 0;
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [] }),
    getPlatformReadiness: async () => ({ data: { overall: "NOT_READY", scope: "PRODUCTION_HANDOFF", components: [], blockingReasonCodes: [] } }),
    probeSearchIndex: async () => { probeCount += 1; return { data: { backend: "opensearch", status: "REACHABLE", reasonCode: "SEARCH_INDEX_PROBE_OK", httpStatus: 200, latencyMs: 7, endpoint: "https://secret.example" } }; },
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.ok(document.querySelector('[data-testid="platform-readiness"]')));
    const panel = document.querySelector('[data-testid="platform-readiness"]');
    const probe = panel.querySelector("[data-testid=search-index-probe]");
    assert.ok(probe);
    await act(async () => probe.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true })));
    await waitFor(() => assert.equal(probeCount, 1));
    await waitFor(() => assert.match(panel.textContent, /SEARCH_INDEX_PROBE_OK/));
    assert.equal(panel.textContent.includes("https://"), false);
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center shows evidence ledger and saves only after explicit action", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  let updateCount = 0;
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [] }),
    getPlatformReadiness: async () => ({ data: { overall: "NOT_READY", scope: "PRODUCTION_HANDOFF", components: [], blockingReasonCodes: [] } }),
    listProductionEvidence: async () => ({ data: [
      { evidenceId: "DATABASE_CAPACITY_SLO", status: "MISSING", revision: 0 },
      { evidenceId: "SSO_ORGANIZATION", status: "ACCEPTED", ownerUserId: "admin", evidenceRef: "change-1", revision: 1 },
    ] }),
    updateProductionEvidence: async () => { updateCount += 1; return { data: { evidenceId: "DATABASE_CAPACITY_SLO", status: "SUBMITTED", revision: 1 } }; },
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.ok(document.querySelector('[data-testid="production-evidence"]')));
    const panel = document.querySelector('[data-testid="production-evidence"]');
    assert.match(panel.textContent, /DATABASE_CAPACITY_SLO/);
    assert.match(panel.textContent, /MISSING/);
    assert.equal(updateCount, 0);
    const save = panel.querySelector('[data-testid="save-production-evidence-DATABASE_CAPACITY_SLO"]');
    assert.ok(save);
    await act(async () => save.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true })));
    await waitFor(() => assert.equal(updateCount, 1));
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center isolates lifecycle health failures from platform metrics", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" }, requests: { total: 4 } } }),
    getOperationsAlerts: async () => ({ data: [] }),
    getSkillLifecycleProjectionReconciliation: async () => { throw new Error("internal database details"); },
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.ok(document.querySelector('[data-testid="lifecycle-health"]')));
    assert.match(document.querySelector(".operations-kpis")?.textContent || "", /4/);
    const panel = document.querySelector('[data-testid="lifecycle-health"]');
    assert.match(panel.textContent, /生命周期观测暂不可用/);
    assert.equal(panel.textContent.includes("internal database details"), false);
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center renders lifecycle projection alert reasons as state alerts", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [{
      rule: "SKILL_LIFECYCLE_PROJECTION",
      eventCode: "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED",
      status: "ACTIVE",
      currentValue: 1,
      threshold: 1,
      unit: "state",
    }] }),
    getSkillLifecycleProjectionReconciliation: async () => ({ data: { backend: "postgresql", state: "DRIFTED", reasonCode: "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED", revision: 7, sourceCounts: {}, projectedCounts: {}, countDelta: {} } }),
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.ok(document.querySelector(".operations-alerts")));
    const panel = document.querySelector(".operations-alerts");
    assert.match(panel.textContent, /Skill 生命周期投影/);
    assert.match(panel.textContent, /SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED/);
    assert.match(panel.textContent, /异常/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center renders production handoff evidence alerts", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [{
      rule: "PRODUCTION_HANDOFF_EVIDENCE",
      eventCode: "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED",
      status: "ACTIVE",
      currentValue: 2,
      threshold: 0,
      unit: "count",
    }] }),
    getPlatformReadiness: async () => ({ data: { overall: "NOT_READY", scope: "PRODUCTION_HANDOFF", components: [], blockingReasonCodes: [] } }),
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, { api, role: "admin" })));
  try {
    await waitFor(() => assert.ok(document.querySelector(".operations-alerts")));
    const panel = document.querySelector(".operations-alerts");
    assert.match(panel.textContent, /生产交付证据/);
    assert.match(panel.textContent, /PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED/);
    assert.match(panel.textContent, /ACTIVE/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center shows stale optimization work items and links to quality management", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  let navigated = "";
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [{
      rule: "OPTIMIZATION_WORK_ITEM_STALENESS",
      eventCode: "OPTIMIZATION_WORK_ITEMS_STALE",
      status: "ACTIVE",
      currentValue: 2,
      threshold: 0,
      unit: "count",
    }] }),
    getOptimizationWorkItemHealth: async () => ({ data: {
      status: "DEGRADED",
      reasonCode: "OPTIMIZATION_WORK_ITEMS_STALE",
      staleThresholdSeconds: 604800,
      activeCount: 4,
      staleCount: 2,
      staleByStatus: { OPEN: 1, IN_PROGRESS: 1 },
      staleByOwner: { "owner-a": 2 },
      staleBySeverity: { HIGH: 1, MEDIUM: 1 },
    } }),
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, { api, role: "admin", onNavigate: (view) => { navigated = view; } })));
  try {
    await waitFor(() => assert.ok(document.querySelector('[data-testid="optimization-work-item-health"]')));
    const panel = document.querySelector('[data-testid="optimization-work-item-health"]');
    assert.match(panel.textContent, /2 个滞留/);
    assert.match(panel.textContent, /OPEN 1/);
    assert.match(panel.textContent, /owner-a 2/);
    const navigateButton = panel.querySelector('[data-testid="optimization-work-item-health-link"]');
    assert.ok(navigateButton);
    await act(async () => navigateButton.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true })));
    assert.equal(navigated, "quality");
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center propagates execution environment filters to runtime and trace queries", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const runtimeCalls = [];
  const traceCalls = [];
  const root = createRoot(document.getElementById("root"));
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [] }),
    getSkillRuntimeMetrics: async (params) => { runtimeCalls.push(params); return { data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }; },
    getTraces: async (params) => { traceCalls.push(params); return { data: [] }; },
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, {
    api,
    role: "admin",
    initialSkillId: "demo-skill",
    initialSkillVersion: "2.0.0",
    initialEnvironmentFilters: { runtimeId: "openclaw", mcpServerId: "mcp-a", llmProviderId: "llm-a" },
    initialTraceFilters: { traceId: "trace-1", status: "failure" },
  })));
  try {
    await waitFor(() => assert.ok(document.querySelector('[aria-label="Runtime ID"]')));
    await waitFor(() => assert.ok(runtimeCalls.some((params) => params.runtimeId === "openclaw" && params.mcpServerId === "mcp-a" && params.llmProviderId === "llm-a")));
    assert.ok(runtimeCalls.some((params) => params.skillId === "demo-skill" && params.version === "2.0.0"));
    assert.ok(traceCalls.some((params) => params.runtimeId === "openclaw" && params.mcpServerId === "mcp-a" && params.llmProviderId === "llm-a"));
    const pageText = document.querySelector(".operations-content")?.textContent || "";
    assert.match(pageText, /Runtime openclaw/);
    assert.match(pageText, /MCP mcp-a/);
    assert.match(pageText, /LLM llm-a/);
    assert.ok(document.querySelector('[aria-label="Trace ID"]'));
    assert.ok(document.querySelector('[aria-label="Trace 状态"]'));
    await waitFor(() => assert.ok(traceCalls.some((params) => params.traceId === "trace-1" && params.status === "failure")));
  } finally {
    await act(async () => root.unmount());
  }
});

test("operations center shows the selected non-failure Trace status in the result panel", async () => {
  document.body.innerHTML = "<div id=\"root\"></div>";
  const root = createRoot(document.getElementById("root"));
  const api = {
    getOperationsMetrics: async () => ({ data: { health: { status: "UP" } } }),
    getOperationsAlerts: async () => ({ data: [] }),
    getSkillRuntimeMetrics: async () => ({ data: { totals: {}, latency: {}, errors: [], sources: [], versionAdoption: [], trend: [] } }),
    getTraces: async () => ({ data: [{ traceId: "trace-success", spanId: "span-success", skillId: "skill-a", version: "1.0.0", operation: "skill.run", status: "success", durationMs: 80, dataSource: "production", occurredAt: "2026-08-21T00:00:00Z" }] }),
  };

  await act(async () => root.render(React.createElement(OperationsMetricsView, {
    api,
    role: "admin",
    initialTraceFilters: { status: "success" },
  })));
  try {
    await waitFor(() => assert.ok(document.querySelector(".trace-row")));
    assert.match(document.querySelector(".trace-failure-panel")?.textContent || "", /Trace 查询结果/);
    assert.match(document.querySelector(".trace-row")?.textContent || "", /成功/);
  } finally {
    await act(async () => root.unmount());
  }
});
