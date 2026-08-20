import test from "node:test";
import assert from "node:assert/strict";
import { DISTRIBUTION_METHODS, buildPromptInstallInstruction, installationStatusTone, isTerminalInstallationStatus, triggerFileDownload } from "../src/distribution.js";

test("distribution methods expose one-click, CLI, and ZIP choices", () => {
  assert.deepEqual(DISTRIBUTION_METHODS.map((method) => method.value), ["one-click", "cli", "manual-zip"]);
});

test("installation status helpers distinguish terminal and failure states", () => {
  assert.equal(isTerminalInstallationStatus("installed"), true);
  assert.equal(isTerminalInstallationStatus("installing"), false);
  assert.equal(installationStatusTone("failed"), "high");
  assert.equal(installationStatusTone("installed"), "success");
});

test("prompt install instruction uses the current skill identity", () => {
  assert.equal(buildPromptInstallInstruction({ id: "skill-center-skill-finder", name: "Skill Finder" }), "使用 skill-center-skill-finder，快速安装 Skill Finder Skill");
});

test("file download uses a browser download link instead of opening a new window", () => {
  const anchor = { clickCalled: false, click() { this.clickCalled = true; }, remove() {} };
  const originalDocument = globalThis.document;
  globalThis.document = { createElement(tag) { assert.equal(tag, "a"); return anchor; } };
  try {
    triggerFileDownload("/api/v1/distribution/artifacts/demo-skill/1.0.0?token=test", "demo-skill-1.0.0.zip");
    assert.equal(anchor.href, "/api/v1/distribution/artifacts/demo-skill/1.0.0?token=test");
    assert.equal(anchor.download, "demo-skill-1.0.0.zip");
    assert.equal(anchor.clickCalled, true);
  } finally {
    globalThis.document = originalDocument;
  }
});
