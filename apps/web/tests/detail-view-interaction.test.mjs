import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { createServer } from "vite";

const webRoot = fileURLToPath(new URL("..", import.meta.url));
const markdown = `---
name: Demo Skill
description: Demonstrates Markdown rendering
---

# 使用说明

- 第一步
- 第二步

\`inline code\`

| 输入 | 输出 |
| --- | --- |
| A | B |
`;

const skill = {
  id: "demo-skill",
  name: "Demo Skill",
  version: "1.0.0",
  description: "Demo",
  category: "other",
  tags: [],
  risk: "low",
  riskTone: "low",
  team: "demo-team",
  owner: "demo-owner",
  status: "published",
  lastUpdated: "2026-08-19",
  publishedAt: "2026-08-19",
  language: "Markdown",
  supportedLanguage: "中文",
  permissionSummary: "只读",
  capabilities: ["没有真实数据源的能力"],
  suitableFor: ["没有真实数据源的适用场景"],
  unsuitableFor: ["没有真实数据源的不适用场景"],
  value: "没有真实数据源的功能价值",
  exampleInput: "没有真实数据源的输入",
  exampleOutput: "没有真实数据源的输出",
  collection: [],
  metrics: { rating: 0, reviews: 0, calls: 0, installs: 0, favorites: 0 },
};

let vite;
let App;
let QualityEnvironmentFilters;
let dom;
let comparisonFixture = false;
let suggestionsFixture = false;
let benchmarkFixture = false;
let qualitySnapshotFixture = false;
let dispositionCalls = [];
let comparisonQuery = null;
let requestLog = [];
let scopeConflictFixture = false;
let scopeFixture = null;
let scopeLoadErrorFixture = null;

function json(data) {
  return new Response(JSON.stringify({ data, requestId: "test" }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function apiError(code, message, status) {
  return new Response(JSON.stringify({
    error: { code, message, details: [] },
    requestId: "test-error",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function responseFor(input, options = {}) {
  const path = String(input);
  requestLog.push({ path, method: options.method || "GET" });
  if (path === "/api/v1/admin/skill-access/scopes?skillId=demo-skill") {
    if (scopeLoadErrorFixture) return apiError(scopeLoadErrorFixture.code, scopeLoadErrorFixture.message, scopeLoadErrorFixture.status);
    return json(scopeFixture ?? {
      visibility: "PUBLIC",
      ownerTeamId: "",
      maintainerUserIds: [],
      revision: 0,
      updatedAt: "",
    });
  }
  if (path === "/api/v1/admin/skill-access/scopes/demo-skill" && (options.method || "GET") === "PUT") {
    if (scopeConflictFixture) return apiError("SKILL_SCOPE_CONFLICT", "stale revision", 409);
    const body = JSON.parse(options.body);
    scopeFixture = {
      visibility: body.visibility,
      ownerTeamId: body.ownerTeamId ?? "",
      maintainerUserIds: body.maintainerUserIds ?? [],
      revision: Number(body.revision ?? 0) + 1,
      updatedAt: "2026-08-21T00:00:00Z",
    };
    return json(scopeFixture);
  }
  if (path === "/api/v1/skills/demo-skill/content") return json(markdown);
  if (path.includes("/api/v1/skills/demo-skill/quality/compare")) {
    comparisonQuery = new URL(path, "http://127.0.0.1").searchParams;
    return json({
    skillId: "demo-skill", baselineVersion: "1.1.0", candidateVersion: "1.0.0", comparable: true,
    reasonCode: "COMPARABLE", reason: "两个版本使用相同评测口径，可直接比较",
    baseline: { score: 90, staticScore: 100, passRate: 1, successRate: 98, p95Ms: 80, totalCalls: 12, gateStatus: "PASSED", dataSource: "mock", runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway", runtimeEnvironment: { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE" } },
    candidate: { score: 80, staticScore: 90, passRate: .8, successRate: 95, p95Ms: 120, totalCalls: 10, gateStatus: "BLOCKED", dataSource: "mock", runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway", runtimeEnvironment: { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE" } },
    delta: { score: -10, staticScore: -10, passRate: -.2, successRate: -3, p95Ms: 40, totalCalls: -2 },
    });
  }
  if (path.includes("/api/v1/skills/demo-skill/quality/suggestions/runtime-latency/disposition")) {
    dispositionCalls.push({ path, options });
    return json({ id: "runtime-latency", severity: "MEDIUM", category: "LATENCY", title: "运行 P95 延迟偏高", evidence: ["p95Ms=1200"], recommendedAction: "检查外部依赖", dispositionStatus: "ACKNOWLEDGED", dispositionNote: "", dispositionBy: "admin", dispositionAt: "2026-08-21T00:00:00Z" });
  }
  if (path.includes("/api/v1/skills/demo-skill/quality/benchmarks")) return json(benchmarkFixture ? [{
    benchmarkId: "benchmark-demo", skillId: "demo-skill", baselineVersion: "0.9.0", candidateVersion: "1.0.0",
    window: "24h", dataSource: "mock", conclusion: "IMPROVED", createdAt: "2026-08-21",
    runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway",
    comparison: { comparable: true, reason: "同口径可比", delta: { score: 8, passRate: .1, p95Ms: -120 }, candidate: { runtimeId: "openclaw", runtimeEnvironment: { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE" } } },
  }] : []);
  if (path.includes("/api/v1/skills/demo-skill/quality/suggestions")) return json(suggestionsFixture ? [{
    id: "runtime-latency", severity: "MEDIUM", category: "LATENCY", title: "运行 P95 延迟偏高",
    evidence: ["p95Ms=1200"], recommendedAction: "检查外部依赖",
  }] : []);
  if (path.startsWith("/api/v1/skills/demo-skill/quality")) return json({
    skillId: "demo-skill",
    version: "1.0.0",
    latestSnapshot: qualitySnapshotFixture ? {
      score: 92, staticScore: 96, passRate: 1, passedCases: 4, totalCases: 4,
      suiteVersion: "suite-1", ruleVersion: "rules-2", runnerId: "mock-runner",
      suiteId: "suite",
      evaluationProviderId: "mock-evaluation", dataSource: "mock",
      runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway",
      runtimeEnvironment: { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v2", revision: 7, status: "ACTIVE" },
      gateStatus: "PASSED", measuredAt: "2026-08-21T00:00:00Z", gateReasons: [],
    } : null,
    snapshotHistory: qualitySnapshotFixture ? [{
      snapshotId: "snapshot-history-1", skillVersion: "0.9.0", score: 88, staticScore: 90,
      passRate: .9, dataSource: "mock", measuredAt: "2026-08-20T00:00:00Z",
      runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway",
      runtimeEnvironment: { kind: "AGENT_RUNTIME", environmentId: "openclaw", version: "runtime-v1", revision: 6, status: "ACTIVE" },
    }] : [],
    runtime: { window: "24h", dataSource: "all", totals: {}, latency: {}, sources: [], errors: [], versionAdoption: [], trend: [] },
    availableVersions: ["1.0.0"],
  });
  if (path === "/api/v1/skills/demo-skill/versions") {
    return json(comparisonFixture
      ? [{ skillId: "demo-skill", version: "1.1.0", status: "published", publishedAt: "2026-08-20" }, { skillId: "demo-skill", version: "1.0.0", status: "published", publishedAt: "2026-08-19" }]
      : [{ skillId: "demo-skill", version: "1.0.0", status: "published", publishedAt: "2026-08-19" }]);
  }
  if (path.startsWith("/api/v1/admin/skill-relations/impact")) return json({
    rootSkillId: "demo-skill", rootVersion: "1.0.0", maxDepth: 5, maxNodes: 100, truncated: true,
    nodes: [{ relationId: "relation-1", skillId: "consumer-skill", version: "2.0.0", relationType: "DEPENDS_ON", depth: 1, status: "published", productionPromoted: false, activeInstallationCount: 3 }],
  });
  if (path === "/api/v1/skills/demo-skill") return json(skill);
  if (path.startsWith("/api/v1/skills?")) return json({ items: [skill], page: 1, pageSize: 12, total: 1 });
  if (path.startsWith("/api/v1/analytics/overview")) return json({ kpis: {}, callTrend: [], topSkills: [] });
  if (path === "/api/v1/me/favorites") return json({ items: [] });
  if (path === "/api/v1/me/notifications") return json({ items: [] });
  if (path === "/api/v1/skills/demo-skill/installations") return json({
    installationId: "installation-demo",
    issuedAt: "2026-08-19T00:00:00Z",
    authorization: { downloadUrl: "/api/v1/distribution/artifacts/demo-skill/1.0.0?token=test" },
  });
  return json({ items: [] });
}

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

async function renderDetail(role = "developer", { scope = null, scopeConflict = false, scopeLoadError = null } = {}) {
  requestLog = [];
  scopeConflictFixture = scopeConflict;
  scopeLoadErrorFixture = scopeLoadError;
  scopeFixture = scope ?? {
    visibility: "PUBLIC",
    ownerTeamId: "",
    maintainerUserIds: [],
    revision: 0,
    updatedAt: "",
  };
  document.body.innerHTML = '<div id="root"></div>';
  window.location.hash = "#/skills/demo-skill";
  window.localStorage.setItem("skill-center.session", JSON.stringify({
    userId: "developer-user",
    role,
    displayName: role === "admin" ? "平台管理员" : "普通开发者",
  }));
  const root = createRoot(document.getElementById("root"));
  await act(async () => root.render(React.createElement(App)));
  await waitFor(() => assert.equal(document.querySelector(".detail-hero h1")?.textContent, "Demo Skill"));
  return root;
}

function buttonByText(text) {
  return [...document.querySelectorAll("button")].find((button) => button.textContent?.includes(text));
}

function setFormValue(element, value) {
  const prototype = element.tagName === "SELECT" ? window.HTMLSelectElement.prototype : window.HTMLInputElement.prototype;
  const descriptor = Object.getOwnPropertyDescriptor(prototype, "value");
  descriptor.set.call(element, value);
  if (element.tagName === "SELECT") {
    element.dispatchEvent(new Event("change", { bubbles: true }));
    return;
  }
  element.dispatchEvent(new Event("input", { bubbles: true }));
  element.dispatchEvent(new Event("change", { bubbles: true }));
}

before(async () => {
  dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://127.0.0.1:5173/#/skills/demo-skill" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: dom.window.navigator });
  globalThis.HTMLElement = dom.window.HTMLElement;
  globalThis.Event = dom.window.Event;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  globalThis.fetch = async (input, options) => responseFor(input, options);
  window.scrollTo = () => {};
  vite = await createServer({ root: webRoot, server: { middlewareMode: true }, appType: "custom", logLevel: "silent" });
  ({ App, QualityEnvironmentFilters } = await vite.ssrLoadModule("/src/App.jsx"));
});

after(async () => {
  await vite?.close();
  dom?.window.close();
});

test("renders SKILL.md as semantic Markdown and removes synthetic detail sections", async () => {
  const root = await renderDetail();
  try {
    await waitFor(() => assert.equal(document.querySelector(".skill-markdown h1")?.textContent, "使用说明"));
    assert.equal(document.querySelector(".skill-markdown pre"), null);
    assert.equal(document.querySelectorAll(".skill-markdown li").length, 2);
    assert.equal(document.querySelectorAll(".skill-markdown table").length, 1);
    assert.doesNotMatch(document.body.textContent, /能力介绍|输入 \/ 输出示例|适用场景|不适用场景|功能价值/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("clicking the version-history tab switches the main panel to the version list", async () => {
  const root = await renderDetail();
  try {
    const tabs = document.querySelector(".detail-main .tabs");
    const historyButton = [...tabs.querySelectorAll("button")].find((button) => button.textContent === "版本历史");
    await act(async () => historyButton.click());
    assert.equal(tabs.querySelector("button.active")?.textContent, "版本历史");
    assert.equal(document.querySelector(".detail-main .skill-markdown"), null);
    assert.equal(document.querySelector(".detail-main .version-history strong")?.textContent, "1.0.0");
  } finally {
    await act(async () => root.unmount());
  }
});

test("admin lifecycle dialog shows read-only downstream relation impact", async () => {
  const root = await renderDetail("admin");
  try {
    const tabs = document.querySelector(".detail-main .tabs");
    const historyButton = [...tabs.querySelectorAll("button")].find((button) => button.textContent === "版本历史");
    await act(async () => historyButton.click());
    await waitFor(() => assert.ok(document.querySelector(".detail-main .version-history")));
    const lifecycleButton = [...document.querySelectorAll(".version-history button")].find((button) => button.textContent === "生命周期操作");
    await act(async () => lifecycleButton.click());
    await waitFor(() => assert.ok(document.querySelector("[data-testid=skill-relation-impact]")));
    const impact = document.querySelector("[data-testid=skill-relation-impact]");
    assert.match(impact.textContent, /consumer-skill/);
    assert.match(impact.textContent, /DEPENDS_ON/);
    assert.match(impact.textContent, /生产未晋级/);
    assert.match(impact.textContent, /3/);
    assert.match(impact.textContent, /影响结果已截断/);
    assert.equal(requestLog.filter((request) => ["POST", "PUT", "PATCH", "DELETE"].includes(request.method)).length, 0);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality tab shows traceable empty metrics and comparison tab explains missing evidence", async () => {
  const root = await renderDetail();
  try {
    const tabs = document.querySelector(".detail-main .tabs");
    const qualityButton = [...tabs.querySelectorAll("button")].find((button) => button.textContent === "质量指标");
    await act(async () => qualityButton.click());
    await waitFor(() => assert.ok(document.querySelector(".detail-quality")));
    assert.match(document.querySelector(".detail-quality")?.textContent || "", /质量分/);
    assert.match(document.querySelector(".detail-quality")?.textContent || "", /暂无质量快照/);
    assert.ok(document.querySelector('[aria-label="执行环境筛选"]'));
    assert.equal(document.querySelector('[aria-label="Runtime ID"]').value, "");

    const compareButton = [...tabs.querySelectorAll("button")].find((button) => button.textContent === "版本对比");
    await act(async () => compareButton.click());
    await waitFor(() => assert.ok(document.querySelector(".detail-comparison")));
    assert.match(document.querySelector(".detail-comparison")?.textContent || "", /暂无可比较的版本快照/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("quality tab shows explainable optimization suggestions with evidence", async () => {
  suggestionsFixture = true;
  const root = await renderDetail();
  try {
    const qualityButton = [...document.querySelectorAll(".detail-main .tabs button")].find((button) => button.textContent === "质量指标");
    await act(async () => qualityButton.click());
    await waitFor(() => assert.ok(document.querySelector(".detail-quality-suggestions")));
    assert.match(document.querySelector(".detail-quality-suggestions")?.textContent || "", /运行 P95 延迟偏高/);
    assert.match(document.querySelector(".detail-quality-suggestions")?.textContent || "", /p95Ms=1200/);
    assert.match(document.querySelector(".detail-quality-suggestions")?.textContent || "", /检查外部依赖/);
  } finally {
    suggestionsFixture = false;
    await act(async () => root.unmount());
  }
});

test("quality tab shows the latest benchmark effect evidence", async () => {
  benchmarkFixture = true;
  const root = await renderDetail();
  try {
    const qualityButton = [...document.querySelectorAll(".detail-main .tabs button")].find((button) => button.textContent === "质量指标");
    await act(async () => qualityButton.click());
    await waitFor(() => assert.ok(document.querySelector(".detail-quality-benchmark")));
    assert.match(document.querySelector(".detail-quality-benchmark")?.textContent || "", /IMPROVED/);
    assert.match(document.querySelector(".detail-quality-benchmark")?.textContent || "", /0.9.0 → 1.0.0/);
    assert.match(document.querySelector(".detail-quality-benchmark")?.textContent || "", /Runtime openclaw/);
    assert.match(document.querySelector(".detail-quality-benchmark")?.textContent || "", /MCP mcp-network/);
    assert.match(document.querySelector(".detail-quality-benchmark")?.textContent || "", /LLM llm-gateway/);
  } finally {
    benchmarkFixture = false;
    await act(async () => root.unmount());
  }
});

test("quality tab shows execution environment on the latest quality snapshot", async () => {
  qualitySnapshotFixture = true;
  const root = await renderDetail();
  try {
    const qualityButton = [...document.querySelectorAll(".detail-main .tabs button")].find((button) => button.textContent === "质量指标");
    await act(async () => qualityButton.click());
    await waitFor(() => assert.ok(document.querySelector(".detail-quality-context")));
    const context = document.querySelector(".detail-quality-context")?.textContent || "";
    assert.match(context, /Runtime：openclaw/);
    assert.match(context, /runtime-v2/);
    assert.match(context, /rev 7/);
    assert.match(context, /ACTIVE/);
    assert.match(context, /MCP：mcp-network/);
    assert.match(context, /LLM：llm-gateway/);
    const history = document.querySelector(".detail-quality-history")?.textContent || "";
    assert.match(history, /Runtime openclaw/);
    assert.match(history, /MCP mcp-network/);
    assert.match(history, /LLM llm-gateway/);
    assert.match(history, /runtime-v1/);
  } finally {
    qualitySnapshotFixture = false;
    await act(async () => root.unmount());
  }
});

test("admin can disposition a quality suggestion and see its persisted status", async () => {
  suggestionsFixture = true;
  dispositionCalls = [];
  const root = await renderDetail("admin");
  try {
    const qualityButton = [...document.querySelectorAll(".detail-main .tabs button")].find((button) => button.textContent === "质量指标");
    await act(async () => qualityButton.click());
    await waitFor(() => assert.ok(document.querySelector(".detail-quality-disposition-actions")));
    assert.ok([...document.querySelectorAll(".detail-quality-actions button")].some((button) => button.textContent.includes("查看运行运营")));
    const acknowledgeButton = [...document.querySelectorAll(".detail-quality-disposition-actions button")].find((button) => button.textContent.includes("确认关注"));
    assert.ok(acknowledgeButton);
    await act(async () => acknowledgeButton.click());
    await waitFor(() => assert.equal(dispositionCalls.length, 1));
    assert.equal(dispositionCalls[0].options.method, "PATCH");
    await waitFor(() => assert.match(document.querySelector(".detail-quality-suggestion")?.textContent || "", /已确认/));
  } finally {
    suggestionsFixture = false;
    dispositionCalls = [];
    await act(async () => root.unmount());
  }
});

test("version comparison renders same-context deltas when both versions have evidence", async () => {
  comparisonFixture = true;
  qualitySnapshotFixture = true;
  comparisonQuery = null;
  const root = await renderDetail();
  try {
    const tabs = document.querySelector(".detail-main .tabs");
    const compareButton = [...tabs.querySelectorAll("button")].find((button) => button.textContent === "版本对比");
    await act(async () => compareButton.click());
    await waitFor(() => assert.ok(document.querySelector(".comparison-result")));
    assert.match(document.querySelector(".comparison-result")?.textContent || "", /同口径可比/);
    assert.match(document.querySelector(".comparison-environment-context")?.textContent || "", /数据来源：Mock/);
    assert.match(document.querySelector(".comparison-result")?.textContent || "", /质量分/);
    assert.match(document.querySelector(".comparison-result")?.textContent || "", /Runtime：openclaw/);
    assert.match(document.querySelector(".comparison-result")?.textContent || "", /MCP：mcp-network/);
    assert.match(document.querySelector(".comparison-result")?.textContent || "", /LLM：llm-gateway/);
    assert.match(document.querySelector(".comparison-environment-context")?.textContent || "", /runtime-v2/);
    assert.match(document.querySelector(".comparison-environment-context")?.textContent || "", /rev 7/);
    assert.equal(comparisonQuery?.get("suiteId"), "suite");
    assert.equal(comparisonQuery?.get("suiteVersion"), "suite-1");
  } finally {
    comparisonFixture = false;
    qualitySnapshotFixture = false;
    comparisonQuery = null;
    await act(async () => root.unmount());
  }
});

test("quality environment filters expose the selected Runtime, MCP and LLM context", async () => {
  document.body.innerHTML = '<div id="quality-filter-root"></div>';
  let applied;
  const root = createRoot(document.getElementById("quality-filter-root"));
  try {
    await act(async () => root.render(React.createElement(QualityEnvironmentFilters, {
      initialFilters: { runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway" },
      onApply: (filters) => { applied = filters; },
    })));
    assert.match(document.body.textContent, /执行环境筛选/);
    assert.equal(document.querySelector('[aria-label="质量数据来源"]').value, "all");
    assert.equal(document.querySelector('[aria-label="Runtime ID"]').value, "openclaw");
    assert.equal(document.querySelector('[aria-label="MCP Server ID"]').value, "mcp-network");
    assert.equal(document.querySelector('[aria-label="LLM Provider ID"]').value, "llm-gateway");
    const submit = [...document.querySelectorAll("button")].find((button) => button.textContent === "应用筛选");
    const source = document.querySelector('[aria-label="质量数据来源"]');
    await act(async () => {
      source.value = "production";
      source.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => submit.click());
    assert.deepEqual(applied, { dataSource: "production", runtimeId: "openclaw", mcpServerId: "mcp-network", llmProviderId: "llm-gateway" });
  } finally {
    await act(async () => root.unmount());
  }
});

test("admin detail view loads scope on entry and only writes after explicit save", async () => {
  const root = await renderDetail("admin");
  try {
    await waitFor(() => assert.ok(requestLog.some((request) => request.path === "/api/v1/admin/skill-access/scopes?skillId=demo-skill")));
    assert.equal(requestLog.filter((request) => request.path === "/api/v1/admin/skill-access/scopes/demo-skill" && request.method === "PUT").length, 0);
    assert.equal(document.querySelector('[aria-label="可见范围"]')?.value, "PUBLIC");
    assert.match(document.querySelector("[data-testid=skill-scope-revision]")?.textContent || "", /0/);

    const visibility = document.querySelector('[aria-label="可见范围"]');
    const ownerTeam = document.querySelector('[aria-label="负责团队 ID"]');
    const maintainers = document.querySelector('[aria-label="维护者用户 ID"]');
    await act(async () => {
      setFormValue(visibility, "TEAM");
      setFormValue(ownerTeam, "team-alpha");
      setFormValue(maintainers, "alice, bob");
    });

    await act(async () => buttonByText("保存范围").click());

    await waitFor(() => assert.equal(requestLog.filter((request) => request.path === "/api/v1/admin/skill-access/scopes/demo-skill" && request.method === "PUT").length, 1));
  } finally {
    scopeLoadErrorFixture = null;
    await act(async () => root.unmount());
  }
});

test("scope not found on a historical skill synthesizes the safe public draft without writing", async () => {
  const root = await renderDetail("admin", {
    scopeLoadError: { code: "SKILL_SCOPE_NOT_FOUND", message: "scope missing", status: 404 },
  });
  try {
    await waitFor(() => assert.ok(requestLog.some((request) => request.path === "/api/v1/admin/skill-access/scopes?skillId=demo-skill")));
    assert.equal(document.querySelector('[aria-label="可见范围"]')?.value, "PUBLIC");
    assert.equal(document.querySelector('[aria-label="负责团队 ID"]')?.value, "");
    assert.equal(document.querySelector('[aria-label="维护者用户 ID"]')?.value, "");
    assert.match(document.querySelector("[data-testid=skill-scope-revision]")?.textContent || "", /0/);
    assert.match(document.body.textContent || "", /历史兼容范围/);
    assert.doesNotMatch(document.body.textContent || "", /范围规则加载失败|scope missing/);
    assert.equal(requestLog.filter((request) => request.path === "/api/v1/admin/skill-access/scopes/demo-skill" && request.method === "PUT").length, 0);
  } finally {
    scopeLoadErrorFixture = null;
    await act(async () => root.unmount());
  }
});

test("non-admin detail view hides governance editing fields", async () => {
  const root = await renderDetail("developer");
  try {
    assert.equal(document.querySelector('[aria-label="可见范围"]'), null);
    assert.equal(buttonByText("保存范围"), undefined);
  } finally {
    await act(async () => root.unmount());
  }
});

test("scope save conflicts preserve the admin draft edits", async () => {
  const root = await renderDetail("admin", {
    scope: {
      visibility: "TEAM",
      ownerTeamId: "team-beta",
      maintainerUserIds: ["owner-1"],
      revision: 3,
      updatedAt: "2026-08-20T00:00:00Z",
    },
    scopeConflict: true,
  });
  try {
    await waitFor(() => assert.equal(document.querySelector('[aria-label="可见范围"]')?.value, "TEAM"));
    const visibility = document.querySelector('[aria-label="可见范围"]');
    const ownerTeam = document.querySelector('[aria-label="负责团队 ID"]');
    const maintainers = document.querySelector('[aria-label="维护者用户 ID"]');
    await act(async () => {
      setFormValue(visibility, "RESTRICTED");
      setFormValue(ownerTeam, "team-gamma");
      setFormValue(maintainers, "owner-2, owner-3");
    });

    await act(async () => buttonByText("保存范围").click());

    await waitFor(() => assert.match(document.body.textContent || "", /范围已被其他管理员更新/));
    assert.equal(document.querySelector('[aria-label="可见范围"]')?.value, "RESTRICTED");
    assert.equal(document.querySelector('[aria-label="负责团队 ID"]')?.value, "team-gamma");
    assert.equal(document.querySelector('[aria-label="维护者用户 ID"]')?.value, "owner-2, owner-3");
  } finally {
    scopeConflictFixture = false;
    await act(async () => root.unmount());
  }
});

test("Prompt 快捷安装 copies a natural-language instruction without opening a dialog", async () => {
  const root = await renderDetail();
  const writes = [];
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: async (value) => writes.push(value) } });
  try {
    const promptButton = [...document.querySelectorAll(".operation button")].find((button) => button.textContent.includes("Prompt 快捷安装"));
    assert.ok(promptButton);
    await act(async () => promptButton.click());
    await waitFor(() => assert.equal(writes[0], "使用 demo-skill，快速安装 Demo Skill Skill"));
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.match(document.body.textContent, /安装指引已复制/);
  } finally {
    await act(async () => root.unmount());
  }
});

test("下载 ZIP requests authorization and triggers a direct browser download without a dialog", async () => {
  const root = await renderDetail();
  const originalCreateElement = document.createElement.bind(document);
  const downloads = [];
  document.createElement = (tagName, options) => {
    const element = originalCreateElement(tagName, options);
    if (tagName === "a") {
      element.click = () => downloads.push({ href: element.href, download: element.download });
    }
    return element;
  };
  try {
    const downloadButton = [...document.querySelectorAll(".operation button")].find((button) => button.textContent.includes("下载 ZIP"));
    assert.ok(downloadButton);
    await act(async () => downloadButton.click());
    await waitFor(() => assert.equal(downloads.length, 1));
    assert.match(downloads[0].href, /\/api\/v1\/distribution\/artifacts\/demo-skill\/1\.0\.0\?token=test/);
    assert.equal(downloads[0].download, "demo-skill-1.0.0.zip");
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.match(document.body.textContent, /ZIP 下载已开始/);
  } finally {
    document.createElement = originalCreateElement;
    await act(async () => root.unmount());
  }
});
