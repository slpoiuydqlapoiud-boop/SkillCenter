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

test("skillApi.getReleaseAdmission calls the read-only admission endpoint", async () => {
  let calledPath = "";
  const api = createSkillApi((path) => {
    calledPath = path;
    return Promise.resolve({ data: { allowed: true } });
  });

  await api.getReleaseAdmission("skill a", "1.0.0");

  assert.equal(calledPath, "/api/v1/admin/releases/admission?skillId=skill%20a&version=1.0.0");
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

test("skillApi exposes resumable upload lifecycle with range headers", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: { uploadId: "upload-1" } });
  });

  await api.startResumableUpload("demo.zip", 6);
  await api.getResumableUpload("upload/1");
  await api.uploadResumableChunk("upload/1", 0, 2, 6, new Blob(["abc"]));
  await api.completeResumableUpload("upload/1");

  assert.equal(calls[0].path, "/api/v1/skill-packages/uploads");
  assert.equal(calls[0].options.method, "POST");
  assert.equal(calls[0].options.body, JSON.stringify({ fileName: "demo.zip", totalBytes: 6 }));
  assert.equal(calls[1].path, "/api/v1/skill-packages/uploads/upload%2F1");
  assert.equal(calls[2].options.headers["Content-Range"], "bytes 0-2/6");
  assert.equal(calls[2].options.headers["Content-Type"], "application/octet-stream");
  assert.equal(calls[3].path, "/api/v1/skill-packages/uploads/upload%2F1/complete");
});

test("skillApi resumes from server progress and reports chunk progress", async () => {
  const calls = [];
  const progress = [];
  const responses = [
    { data: { uploadId: "upload-1", totalBytes: 6, receivedBytes: 3, chunkSize: 3, status: "uploading" } },
    { data: { uploadId: "upload-1", totalBytes: 6, receivedBytes: 6, chunkSize: 3, status: "ready" } },
    { data: { status: "pending_review" } },
  ];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve(responses.shift());
  });

  const result = await api.uploadPackageResumable(new Blob(["abcdef"]), {
    uploadId: "upload-1",
    onProgress: (snapshot) => progress.push(snapshot),
  });

  assert.equal(result.data.status, "pending_review");
  assert.equal(calls[0].path, "/api/v1/skill-packages/uploads/upload-1");
  assert.equal(calls[1].options.headers["Content-Range"], "bytes 3-5/6");
  assert.equal(calls[1].options.body.size, 3);
  assert.deepEqual(progress.map((item) => item.receivedBytes), [3, 6]);
});

test("skillApi cancels a resumable upload with an encoded session id", async () => {
  let captured;
  const api = createSkillApi((path, options = {}) => {
    captured = { path, options };
    return Promise.resolve({});
  });

  await api.cancelResumableUpload("upload/1");

  assert.equal(captured.path, "/api/v1/skill-packages/uploads/upload%2F1");
  assert.equal(captured.options.method, "DELETE");
});

test("skillApi.getSkillScope uses the admin scope endpoint with encoded skillId", async () => {
  let calledPath = "";
  const api = createSkillApi((path) => {
    calledPath = path;
    return Promise.resolve({ data: { visibility: "PUBLIC", ownerTeamId: "", maintainerUserIds: [], revision: 0 } });
  });

  await api.getSkillScope("skill a/b");

  assert.equal(calledPath, "/api/v1/admin/skill-access/scopes?skillId=skill%20a%2Fb");
});

test("skillApi.updateSkillScope sends only the safe request body fields", async () => {
  let captured;
  const api = createSkillApi((path, options = {}) => {
    captured = { path, options };
    return Promise.resolve({ data: { visibility: "TEAM", ownerTeamId: "team-a", maintainerUserIds: ["alice"], revision: 2 } });
  });

  await api.updateSkillScope("skill a/b", {
    visibility: "TEAM",
    ownerTeamId: "team-a",
    maintainerUserIds: ["alice"],
    revision: 1,
    declaredBy: "admin",
    updatedBy: "admin",
    prompt: "hidden",
    traceText: "hidden",
    toolArgs: ["hidden"],
    token: "secret",
    credentials: { apiKey: "secret" },
    providerExceptionText: "hidden",
  });

  assert.equal(captured.path, "/api/v1/admin/skill-access/scopes/skill%20a%2Fb");
  assert.equal(captured.options.method, "PUT");
  assert.deepEqual(JSON.parse(captured.options.body), {
    visibility: "TEAM",
    ownerTeamId: "team-a",
    maintainerUserIds: ["alice"],
    revision: 1,
  });
});

test("skillApi exposes optimization experiment lifecycle endpoints", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.listOptimizationExperiments({ skillId: "skill-a", status: "RUNNING" });
  await api.createOptimizationExperiment({ workItemId: "work-1" });
  await api.reconcileOptimizationExperiment("experiment-1");
  await api.cancelOptimizationExperiment("experiment-1");
  await api.benchmarkOptimizationExperiment("experiment-1", { window: "24h" });
  await api.decideOptimizationExperiment("experiment-1");
  await api.getOptimizationExperimentDecision("experiment-1");
  await api.listOptimizationExperimentObservations("experiment-1");
  await api.captureOptimizationExperimentObservation("experiment-1", { window: "24h" });
  await api.listOptimizationExperimentAssessments("experiment-1");
  await api.createOptimizationExperimentAssessment("experiment-1", { observationId: "observation-1", action: "KEEP", note: "保留" });
  await api.getOptimizationExperimentAssessment("experiment-1", "assessment-1");

  assert.deepEqual(calls.map((call) => call.path), [
    "/api/v1/admin/quality/optimization-experiments?skillId=skill-a&status=RUNNING",
    "/api/v1/admin/quality/optimization-experiments",
    "/api/v1/admin/quality/optimization-experiments/experiment-1/reconcile",
    "/api/v1/admin/quality/optimization-experiments/experiment-1/cancel",
    "/api/v1/admin/quality/optimization-experiments/experiment-1/benchmark",
    "/api/v1/admin/quality/optimization-experiments/experiment-1/decision",
    "/api/v1/admin/quality/optimization-experiments/experiment-1/decision",
    "/api/v1/admin/quality/optimization-experiments/experiment-1/observations",
    "/api/v1/admin/quality/optimization-experiments/experiment-1/observations",
    "/api/v1/admin/quality/optimization-experiments/experiment-1/assessments",
    "/api/v1/admin/quality/optimization-experiments/experiment-1/assessments",
    "/api/v1/admin/quality/optimization-experiments/experiment-1/assessments/assessment-1",
  ]);
  assert.equal(calls[1].options.method, "POST");
  assert.equal(calls[1].options.body, JSON.stringify({ workItemId: "work-1" }));
  assert.equal(calls[4].options.body, JSON.stringify({ window: "24h" }));
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

test("api client exchanges local credentials for a bearer session", async () => {
  const calls = [];
  const client = createApiClient({
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return new Response(JSON.stringify({
        data: { token: "token-1", actor: { userId: "admin", role: "admin" }, expiresAt: "2026-09-09T10:00:00Z" },
      }), { status: 200, headers: { "content-type": "application/json" } });
    },
  });

  const session = await client.login("admin", "secret");
  await client.request("/api/v1/admin/reviews");

  assert.equal(session.token, "token-1");
  assert.equal(calls[0].url, "/api/v1/auth/login");
  assert.equal(calls[1].options.headers.Authorization, "Bearer token-1");
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

test("api client exposes quality gate reasons when publish is blocked", async () => {
  const client = createApiClient({
    fetchImpl: async () => new Response(JSON.stringify({
      error: {
        code: "QUALITY_GATE_BLOCKED",
        message: "Quality gate must pass before publishing this Skill version",
        details: [{ path: "qualityGate", reason: "SCORE_BELOW_THRESHOLD" }],
      },
    }), { status: 409, headers: { "content-type": "application/json" } }),
  });

  await assert.rejects(client.request("/api/v1/admin/reviews/review-1/approve", { method: "POST" }), (error) => {
    assert.equal(error.code, "QUALITY_GATE_BLOCKED");
    assert.match(error.message, /质量门禁未通过/);
    assert.match(error.message, /SCORE_BELOW_THRESHOLD/);
    assert.equal(error.details[0].reason, "SCORE_BELOW_THRESHOLD");
    return true;
  });
});

test("api client gives actionable guidance for optimization release-gate blocks", async () => {
  const client = createApiClient({
    fetchImpl: async () => new Response(JSON.stringify({
      error: {
        code: "QUALITY_GATE_BLOCKED",
        message: "Quality gate must pass before publishing this Skill version",
        details: [{ path: "qualityGate", reason: "OPTIMIZATION_DECISION_REQUIRED" }],
      },
    }), { status: 409, headers: { "content-type": "application/json" } }),
  });

  await assert.rejects(client.request("/api/v1/admin/reviews/review-optimization/approve", { method: "POST" }), (error) => {
    assert.equal(error.code, "QUALITY_GATE_BLOCKED");
    assert.match(error.message, /优化实验门禁未通过/);
    assert.match(error.message, /质量管理中心/);
    assert.match(error.message, /尚未生成决策/);
    return true;
  });
});

test("api client explains review state conflicts without exposing internal details", async () => {
  const api = createApiClient({
    fetchImpl: async () => new Response(JSON.stringify({
      error: { code: "REVIEW_STATE_CONFLICT", message: "Security reviewer must be different from the submitter and ordinary reviewer" },
      requestId: "review-conflict-1",
    }), { status: 409, headers: { "Content-Type": "application/json" } }),
  });

  await assert.rejects(() => api.request("/api/v1/admin/reviews/review-1/approve"), (error) => {
    assert.equal(error.code, "REVIEW_STATE_CONFLICT");
    assert.match(error.message, /审核状态已变化|需要另一名审核人/);
    assert.doesNotMatch(error.message, /Security reviewer/);
    return true;
  });
});

test("api client explains immutable Skill version conflicts", async () => {
  const api = createApiClient({
    fetchImpl: async () => new Response(JSON.stringify({
      error: { code: "SKILL_VERSION_CONFLICT", message: "Skill version already exists or is not newer than the latest version" },
    }), { status: 409, headers: { "Content-Type": "application/json" } }),
  });

  await assert.rejects(() => api.request("/api/v1/skill-packages"), (error) => {
    assert.equal(error.code, "SKILL_VERSION_CONFLICT");
    assert.match(error.message, /版本已存在|必须高于最新版本/);
    return true;
  });
});

test("api client explains runner execution boundary errors", async () => {
  const api = createApiClient({
    fetchImpl: async () => new Response(JSON.stringify({
      error: { code: "RUNNER_VERSION_NOT_ALLOWED", message: "Runner 只能执行已发布的 Skill 版本" },
    }), { status: 409, headers: { "Content-Type": "application/json" } }),
  });

  await assert.rejects(() => api.request("/api/v1/admin/runner/executions", { method: "POST" }), (error) => {
    assert.equal(error.code, "RUNNER_VERSION_NOT_ALLOWED");
    assert.match(error.message, /只能对已发布的 Skill 版本执行评测/);
    return true;
  });
});

test("skillApi exposes governance review, installation and audit queries", async () => {
  const calls = [];
  const api = createSkillApi((path, options) => {
    calls.push({ path, options });
    return Promise.resolve({ data: [] });
  });

  await api.listReviews();
  await api.listReviews("pending_review");
  await api.approveReview("review-1");
  await api.rejectReview("review-1", "not ready");
  await api.listInstallations();
  await api.listAudit({ action: "INSTALL_REQUESTED" });

  assert.deepEqual(calls.map((call) => call.path), [
    "/api/v1/admin/reviews",
    "/api/v1/admin/reviews?status=pending_review",
    "/api/v1/admin/reviews/review-1/approve",
    "/api/v1/admin/reviews/review-1/reject",
    "/api/v1/installations",
    "/api/v1/audit?action=INSTALL_REQUESTED",
  ]);
  assert.equal(calls[3].options.method, "POST");
  assert.equal(calls[3].options.body, JSON.stringify({ reason: "not ready" }));
});

test("skillApi exposes controlled release lifecycle endpoints", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.listReleases({ skillId: "skill-a", version: "1.0.0", targetEnvironment: "STAGING" });
  await api.getRelease("release-1");
  await api.createRelease({ skillId: "skill-a", version: "1.0.0", targetEnvironment: "STAGING", idempotencyKey: "idem-1" });
  await api.approveRelease("release-1");
  await api.rejectRelease("release-1", "质量门禁未满足");
  await api.promoteRelease("release-1");
  await api.requestReleaseRollbackReview("release-1", { reason: "regression", targetVersion: "0.9.0" });
  await api.rollbackRelease("release-1");

  assert.deepEqual(calls.map((call) => call.path), [
    "/api/v1/admin/releases?skillId=skill-a&version=1.0.0&targetEnvironment=STAGING",
    "/api/v1/admin/releases/release-1",
    "/api/v1/admin/releases",
    "/api/v1/admin/releases/release-1/approve",
    "/api/v1/admin/releases/release-1/reject",
    "/api/v1/admin/releases/release-1/promote",
    "/api/v1/admin/releases/release-1/rollback-review",
    "/api/v1/admin/releases/release-1/rollback",
  ]);
  assert.equal(calls[2].options.method, "POST");
  assert.equal(calls[4].options.body, JSON.stringify({ reason: "质量门禁未满足" }));
  assert.equal(calls[6].options.body, JSON.stringify({ reason: "regression", targetVersion: "0.9.0" }));
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
  await api.getSkillRelationImpact("skill a", "1.0.0", { maxDepth: 1, maxNodes: 2 });
  await api.listSkillRelations({ sourceSkillId: "skill-a", status: "ACTIVE" });
  await api.createSkillRelation({ sourceSkillId: "skill-a", sourceVersion: "1.0.0", targetSkillId: "skill-b", targetVersion: "2.0.0", relationType: "DEPENDS_ON" });
  await api.retireSkillRelation("relation-1", "migration");
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
    "/api/v1/admin/skill-relations/impact?skillId=skill%20a&version=1.0.0&maxDepth=1&maxNodes=2",
    "/api/v1/admin/skill-relations?sourceSkillId=skill-a&status=ACTIVE",
    "/api/v1/admin/skill-relations",
    "/api/v1/admin/skill-relations/relation-1/retire",
    "/api/v1/me/installations?status=installed&clientType=codex",
    "/api/v1/me/skills",
    "/api/v1/me/favorites",
    "/api/v1/me/favorites/eox-query",
    "/api/v1/me/favorites/eox-query",
    "/api/v1/me/invocations?page=2&pageSize=10",
  ]);
  assert.equal(calls[1].options.method, "POST");
  assert.equal(calls[6].options.method, "POST");
  assert.equal(calls[6].options.body, JSON.stringify({ sourceSkillId: "skill-a", sourceVersion: "1.0.0", targetSkillId: "skill-b", targetVersion: "2.0.0", relationType: "DEPENDS_ON" }));
  assert.equal(calls[7].options.method, "POST");
  assert.equal(calls[7].options.body, JSON.stringify({ reason: "migration" }));
  assert.equal(calls[12].options.method, "DELETE");
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

test("skillApi exposes provider contract verification", async () => {
  const calls = [];
  const api = createSkillApi((path) => {
    calls.push(path);
    return Promise.resolve({ data: [] });
  });

  await api.getQualityProviderContractVerification();

  assert.deepEqual(calls, ["/api/v1/admin/quality/provider-contract-verification"]);
});

test("skillApi exposes package security readiness", async () => {
  const calls = [];
  const api = createSkillApi((path) => {
    calls.push(path);
    return Promise.resolve({ data: null });
  });

  await api.getPackageSecurityReadiness();

  assert.deepEqual(calls, ["/api/v1/admin/package-security/readiness"]);
});

test("skillApi exposes artifact storage connectivity probe", async () => {
  const calls = [];
  const api = createSkillApi((path, options) => {
    calls.push({ path, options });
    return Promise.resolve({ data: null });
  });

  await api.probeArtifactStorage();

  assert.deepEqual(calls, [{
    path: "/api/v1/admin/platform/artifact-storage/probe",
    options: { method: "POST" },
  }]);
});
