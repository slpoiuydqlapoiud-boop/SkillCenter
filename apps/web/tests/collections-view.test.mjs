import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { createServer } from "vite";

const webRoot = fileURLToPath(new URL("..", import.meta.url));
let vite;
let CollectionsView;
let dom;

const collection = {
  collectionId: "network-ops",
  name: "网络运维助手合集",
  description: "面向网络巡检、拓扑分析和故障定位的常用能力包。",
  ownerTeamId: "网络平台团队",
  skillIds: ["eox-query", "topology-analysis", "incident-assistant"],
  lastUpdated: "2026-08-19",
};

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

async function renderCollections() {
  document.body.innerHTML = '<div id="root"></div>';
  const root = createRoot(document.getElementById("root"));
  const api = {
    listPublicCollections: async () => ({ data: { items: [collection], total: 1 } }),
    getPublicCollection: async () => ({ data: { collection, skills: [] } }),
  };
  await act(async () => root.render(React.createElement(CollectionsView, { api, role: "developer" })));
  await waitFor(() => assert.equal(document.querySelector(".collection-card strong")?.textContent, collection.name));
  return root;
}

before(async () => {
  dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://127.0.0.1:5173/#/collection" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: dom.window.navigator });
  globalThis.HTMLElement = dom.window.HTMLElement;
  globalThis.Event = dom.window.Event;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  vite = await createServer({ root: webRoot, server: { middlewareMode: true }, appType: "custom", logLevel: "silent" });
  ({ CollectionsView } = await vite.ssrLoadModule("/src/CollectionsView.jsx"));
});

after(async () => {
  await vite?.close();
  dom?.window.close();
});

test("collection grid reuses market card grid and keeps long names readable", async () => {
  const root = await renderCollections();
  try {
    assert.ok(document.querySelector(".collection-grid.skill-grid"));
    const cardTitle = document.querySelector(".collection-card .skill-card-title strong");
    assert.ok(cardTitle);
    assert.equal(cardTitle.classList.contains("collection-card-title"), true);
  } finally {
    await act(async () => root.unmount());
  }
});

test("collection controls and list mode use the same icons and sorting language as the market", async () => {
  const root = await renderCollections();
  try {
    assert.ok(document.querySelector('.collection-filterbar .ph-magnifying-glass'));
    assert.ok(document.querySelector('.collection-filterbar .ph-squares-four'));
    assert.ok(document.querySelector('.collection-filterbar .ph-list'));
    assert.ok([...document.querySelector('[aria-label="排序方式"]').options].some((option) => option.textContent === "最近更新"));
    await act(async () => document.querySelector('[aria-label="列表视图"]').click());
    assert.ok(document.querySelector(".collection-list .skill-list-row .ph-caret-right"));
    assert.ok(document.querySelector('.collection-pagination .ph-caret-left'));
    assert.ok(document.querySelector('.collection-pagination .ph-caret-right'));
  } finally {
    await act(async () => root.unmount());
  }
});
