import test from "node:test";
import assert from "node:assert/strict";
import { buildAnalyticsParams, validateCustomRange } from "../src/analytics.js";

test("buildAnalyticsParams preserves custom dates and dimensions", () => {
  assert.deepEqual(buildAnalyticsParams("custom", "2026-08-01", "2026-08-31", {
    skillId: "eox-query", teamId: "network-team", clientType: "codex",
  }), {
    range: "custom", from: "2026-08-01", to: "2026-08-31",
    skillId: "eox-query", teamId: "network-team", clientType: "codex",
  });
});

test("validateCustomRange rejects reversed and overlong dates", () => {
  assert.equal(validateCustomRange("2026-08-01", "2026-08-31"), "");
  assert.match(validateCustomRange("2026-08-31", "2026-08-01"), /起始日期/);
  assert.match(validateCustomRange("2026-01-01", "2026-04-01"), /90/);
});
