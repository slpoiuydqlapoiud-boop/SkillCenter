import test from "node:test";
import assert from "node:assert/strict";
import { createSkillApi } from "../src/api/skillApi.js";
import { getNavigationForRole } from "../src/state.js";
import { normalizeOperationsAlerts, normalizeOperationsMetrics } from "../src/operationsMetrics.js";

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

test("only admin navigation exposes operations monitoring", () => {
  assert.ok(getNavigationForRole("admin").includes("operations"));
  assert.equal(getNavigationForRole("viewer").includes("operations"), false);
  assert.equal(getNavigationForRole("reviewer").includes("operations"), false);
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
