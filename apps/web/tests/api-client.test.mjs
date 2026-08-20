import test from "node:test";
import assert from "node:assert/strict";
import { createSkillApi } from "../src/api/skillApi.js";
import { createApiClient } from "../src/api/client.js";

test("skillApi.listSkills calls the versioned skills endpoint", async () => {
  const calls = [];
  const api = createSkillApi((path) => {
    calls.push(path);
    return Promise.resolve({ data: { items: [] } });
  });

  await api.listSkills({ query: "EOX", page: 1, pageSize: 12 });

  assert.equal(calls[0], "/api/v1/skills?query=EOX&page=1&pageSize=12");

  await api.listSkills({ sort: "calls", page: 2, pageSize: 12 });
  assert.equal(calls[1], "/api/v1/skills?sort=calls&page=2&pageSize=12");
});

test("skillApi.getSkillContent calls the skill content endpoint", async () => {
  let calledPath = "";
  const api = createSkillApi((path) => {
    calledPath = path;
    return Promise.resolve({ data: "# Skill content" });
  });

  const response = await api.getSkillContent("skill only");

  assert.equal(calledPath, "/api/v1/skills/skill%20only/content");
  assert.equal(response.data, "# Skill content");
});

test("skillApi.uploadPackage sends multipart form data", async () => {
  let captured;
  const api = createSkillApi((path, options) => {
    captured = { path, options };
    return Promise.resolve({ data: { status: "validated" } });
  });
  const file = new Blob(["zip"], { type: "application/zip" });

  await api.uploadPackage(file);

  assert.equal(captured.path, "/api/v1/skill-packages");
  assert.equal(captured.options.method, "POST");
  assert.ok(captured.options.body instanceof FormData);
});

test("api client sends local actor headers and allows role switching", async () => {
  const calls = [];
  const client = createApiClient({
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return new Response(JSON.stringify({ data: {} }), { status: 200, headers: { "content-type": "application/json" } });
    },
  });

  await client.request("/api/v1/admin/reviews");
  client.setActor({ userId: "alice", role: "reviewer" });
  await client.request("/api/v1/admin/reviews");

  assert.equal(calls[0].options.headers["X-User-Role"], "admin");
  assert.equal(calls[1].options.headers["X-User-Id"], "alice");
  assert.equal(calls[1].options.headers["X-User-Role"], "reviewer");
});

test("api client exposes retry metadata and friendly rate-limit message", async () => {
  const client = createApiClient({
    fetchImpl: async () => new Response(JSON.stringify({
      error: { code: "RATE_LIMITED", message: "Request rate limit exceeded", details: [] },
      requestId: "request-rate-limit",
    }), { status: 429, headers: { "content-type": "application/json", "Retry-After": "7" } }),
  });

  await assert.rejects(client.request("/api/v1/events/invocations"), (error) => {
    assert.equal(error.code, "RATE_LIMITED");
    assert.equal(error.status, 429);
    assert.equal(error.retryAfterSeconds, 7);
    assert.equal(error.requestId, "request-rate-limit");
    assert.match(error.message, /7/);
    return true;
  });
});

test("api client explains rejected browser origin", async () => {
  const client = createApiClient({
    fetchImpl: async () => new Response(JSON.stringify({
      error: { code: "CSRF_ORIGIN_REJECTED", message: "Request origin is not allowed", details: [] },
      requestId: "request-origin",
    }), { status: 403, headers: { "content-type": "application/json" } }),
  });

  await assert.rejects(client.request("/api/v1/admin/exports", { method: "POST" }), (error) => {
    assert.equal(error.code, "CSRF_ORIGIN_REJECTED");
    assert.match(error.message, /来源/);
    return true;
  });
});

test("api client includes concrete Skill package validation details", async () => {
  const client = createApiClient({
    fetchImpl: async () => new Response(JSON.stringify({
      error: {
        code: "PACKAGE_VALIDATION_FAILED",
        message: "Skill package validation failed",
        details: [
          { path: "skill.json", reason: "缺少必填字段 name" },
          { path: "SKILL.md", reason: "缺少 name 和 description frontmatter" },
        ],
      },
      requestId: "request-package-validation",
    }), { status: 400, headers: { "content-type": "application/json" } }),
  });

  await assert.rejects(client.request("/api/v1/skill-packages", { method: "POST" }), (error) => {
    assert.equal(error.code, "PACKAGE_VALIDATION_FAILED");
    assert.match(error.message, /skill\.json/);
    assert.match(error.message, /缺少必填字段 name/);
    assert.match(error.message, /SKILL\.md/);
    return true;
  });
});

test("skillApi exposes governance review, installation and audit queries", async () => {
  const calls = [];
  const api = createSkillApi((path, options) => {
    calls.push({ path, options });
    return Promise.resolve({ data: [] });
  });

  await api.listReviews("pending_review");
  await api.approveReview("review-1");
  await api.rejectReview("review-1", "not ready");
  await api.listInstallations();
  await api.listAudit({ action: "INSTALL_REQUESTED" });

  assert.deepEqual(calls.map((call) => call.path), [
    "/api/v1/admin/reviews?status=pending_review",
    "/api/v1/admin/reviews/review-1/approve",
    "/api/v1/admin/reviews/review-1/reject",
    "/api/v1/installations",
    "/api/v1/audit?action=INSTALL_REQUESTED",
  ]);
  assert.equal(calls[2].options.method, "POST");
  assert.equal(calls[2].options.body, JSON.stringify({ reason: "not ready" }));
});

test("skillApi lists public collections with server query state", async () => {
  const calls = [];
  const api = createSkillApi((path) => {
    calls.push(path);
    return Promise.resolve({ data: { items: [], total: 0 } });
  });

  await api.listPublicCollections({ query: "网络", sort: "calls", page: 2, pageSize: 12 });

  assert.equal(calls[0], "/api/v1/collections?query=%E7%BD%91%E7%BB%9C&sort=calls&page=2&pageSize=12");
});

test("skillApi exposes persisted notification actions", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push([path, options.method || "GET"]);
    return Promise.resolve({ data: { items: [], unreadCount: 0 } });
  });

  await api.listNotifications();
  await api.readNotification("n-1");
  await api.readAllNotifications();

  assert.deepEqual(calls, [
    ["/api/v1/me/notifications", "GET"],
    ["/api/v1/me/notifications/n-1/read", "PUT"],
    ["/api/v1/me/notifications/read-all", "POST"],
  ]);
});

test("skillApi exposes distribution methods and installation detail", async () => {
  const calls = [];
  const api = createSkillApi((path, options) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.createInstallation("eox-query", { clientType: "codex", clientVersion: "1.0.0", method: "cli" });
  await api.getInstallation("installation-1");
  await api.consumeDistributionToken("token-1", "short-token");

  assert.deepEqual(calls.map((call) => call.path), [
    "/api/v1/skills/eox-query/installations",
    "/api/v1/installations/installation-1",
    "/api/v1/distribution/authorizations/token-1/consume",
  ]);
  assert.equal(calls[0].options.body, JSON.stringify({ clientType: "codex", clientVersion: "1.0.0", method: "cli" }));
  assert.equal(calls[2].options.body, JSON.stringify({ token: "short-token" }));
});

test("skillApi.getAnalyticsOverview encodes range and filters", async () => {
  const calls = [];
  const api = createSkillApi((path) => {
    calls.push(path);
    return Promise.resolve({ data: {} });
  });

  await api.getAnalyticsOverview({ range: "30d", skillId: "eox-query" });

  assert.equal(calls[0], "/api/v1/analytics/overview?range=30d&skillId=eox-query");
});

test("skillApi exposes lifecycle and personal center endpoints", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.listVersions("eox-query");
  await api.deprecateVersion("eox-query", "1.0.0", { reason: "security", replacementVersion: "1.1.0" });
  await api.withdrawVersion("eox-query", "1.0.0", { reason: "critical" });
  await api.getVersionImpact("eox-query", "1.0.0");
  await api.listMyInstallations({ status: "installed", clientType: "codex" });
  await api.listMySkills();
  await api.listFavorites();
  await api.addFavorite("eox-query");
  await api.removeFavorite("eox-query");
  await api.listMyInvocations({ page: 2, pageSize: 10 });

  assert.deepEqual(calls.map((call) => call.path), [
    "/api/v1/skills/eox-query/versions",
    "/api/v1/skills/eox-query/versions/1.0.0/deprecate",
    "/api/v1/skills/eox-query/versions/1.0.0/withdraw",
    "/api/v1/skills/eox-query/versions/1.0.0/impact",
    "/api/v1/me/installations?status=installed&clientType=codex",
    "/api/v1/me/skills",
    "/api/v1/me/favorites",
    "/api/v1/me/favorites/eox-query",
    "/api/v1/me/favorites/eox-query",
    "/api/v1/me/invocations?page=2&pageSize=10",
  ]);
  assert.equal(calls[1].options.method, "POST");
  assert.equal(calls[8].options.method, "DELETE");
});

test("skillApi exposes controlled export and retention governance endpoints", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.createExport({ dataset: "AUDIT_SUMMARY", format: "CSV", filters: {} });
  await api.listExports({ status: "COMPLETED", dataset: "AUDIT_SUMMARY" });
  await api.getExport("job-1");
  await api.issueExportDownloadUrl("job-1");
  await api.retryExport("job-1");
  await api.getRetentionPolicy();
  await api.updateRetentionPolicy({ policyVersion: 1, auditRetentionDays: 365, invocationRetentionDays: 90, installationRetentionDays: 90 });
  await api.previewRetention();
  await api.executeRetention({ previewId: "preview-1", policyVersion: 1, executionId: "batch-1" });

  assert.deepEqual(calls.map((call) => call.path), [
    "/api/v1/admin/exports",
    "/api/v1/admin/exports?status=COMPLETED&dataset=AUDIT_SUMMARY",
    "/api/v1/admin/exports/job-1",
    "/api/v1/admin/exports/job-1/download-url",
    "/api/v1/admin/exports/job-1/retry",
    "/api/v1/admin/retention",
    "/api/v1/admin/retention",
    "/api/v1/admin/retention/preview",
    "/api/v1/admin/retention/execute",
  ]);
  assert.equal(calls[0].options.method, "POST");
  assert.equal(calls[0].options.body, JSON.stringify({ dataset: "AUDIT_SUMMARY", format: "CSV", filters: {} }));
  assert.equal(calls[6].options.method, "PUT");
  assert.equal(calls[8].options.method, "POST");
});
