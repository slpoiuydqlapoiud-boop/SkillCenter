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
let dom;

function json(data) {
  return new Response(JSON.stringify({ data, requestId: "test" }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function responseFor(input) {
  const path = String(input);
  if (path === "/api/v1/skills/demo-skill/content") return json(markdown);
  if (path === "/api/v1/skills/demo-skill/versions") {
    return json([{ skillId: "demo-skill", version: "1.0.0", status: "published", publishedAt: "2026-08-19" }]);
  }
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

async function renderDetail() {
  document.body.innerHTML = '<div id="root"></div>';
  window.location.hash = "#/skills/demo-skill";
  window.localStorage.setItem("skill-center.session", JSON.stringify({
    userId: "developer-user",
    role: "developer",
    displayName: "普通开发者",
  }));
  const root = createRoot(document.getElementById("root"));
  await act(async () => root.render(React.createElement(App)));
  await waitFor(() => assert.equal(document.querySelector(".detail-hero h1")?.textContent, "Demo Skill"));
  return root;
}

before(async () => {
  dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://127.0.0.1:5173/#/skills/demo-skill" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: dom.window.navigator });
  globalThis.HTMLElement = dom.window.HTMLElement;
  globalThis.Event = dom.window.Event;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  globalThis.fetch = async (input) => responseFor(input);
  window.scrollTo = () => {};
  vite = await createServer({ root: webRoot, server: { middlewareMode: true }, appType: "custom", logLevel: "silent" });
  ({ App } = await vite.ssrLoadModule("/src/App.jsx"));
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
