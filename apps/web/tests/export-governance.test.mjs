import test from "node:test";
import assert from "node:assert/strict";
import {
  canViewExportWorkbench,
  auditMetadataEntries,
  formatExportError,
  formatExportStatus,
  isExportTerminal,
  normalizeAuditEvents,
  normalizeRetentionPreview,
  validateRetentionForm,
} from "../src/exportGovernance.js";

test("export workbench is visible only to audit and admin roles", () => {
  assert.equal(canViewExportWorkbench("reviewer"), true);
  assert.equal(canViewExportWorkbench("admin"), true);
  assert.equal(canViewExportWorkbench("viewer"), false);
  assert.equal(canViewExportWorkbench("maintainer"), false);
});

test("export statuses and terminal states are stable", () => {
  assert.equal(formatExportStatus("QUEUED"), "排队中");
  assert.equal(formatExportStatus("COMPLETED"), "已完成");
  assert.equal(formatExportStatus("FAILED"), "失败");
  assert.equal(isExportTerminal("COMPLETED"), true);
  assert.equal(isExportTerminal("RUNNING"), false);
});

test("retention form validates safety floors", () => {
  assert.deepEqual(validateRetentionForm({ auditRetentionDays: 364, invocationRetentionDays: 90, installationRetentionDays: 90 }), {
    auditRetentionDays: "审计数据至少保留 365 天",
  });
  assert.deepEqual(validateRetentionForm({ auditRetentionDays: 365, invocationRetentionDays: 30, installationRetentionDays: 30 }), {});
});

test("retention preview keeps quality evidence counts with safe defaults", () => {
  assert.deepEqual(normalizeRetentionPreview({ invocationEligibleCount: 2, qualityEvidenceEligibleCount: "3", runnerExecutionEligibleCount: 4 }), {
    invocationEligibleCount: 2,
    installationEligibleCount: 0,
    auditArchiveEligibleCount: 0,
    runtimeSummaryEligibleCount: 0,
    qualityEvidenceEligibleCount: 3,
    benchmarkEligibleCount: 0,
    runnerExecutionEligibleCount: 4,
    compatibilityMatrixEligibleCount: 0,
    protectedEvaluationRunCount: 0,
    protectedQualitySnapshotCount: 0,
    protectedBenchmarkCount: 0,
    protectedCompatibilityMatrixCount: 0,
    protectedReferenceCount: 0,
    protectionFingerprint: "",
  });
});

test("export errors never expose internal messages", () => {
  assert.equal(formatExportError({ code: "EXPORT_EXPIRED" }), "导出文件已过期，请重新生成");
  assert.match(formatExportError({ code: "RETENTION_PROTECTION_CONFLICT" }), /未执行清理/);
  assert.match(formatExportError({ code: "RETENTION_EVIDENCE_PROTECTION_UNAVAILABLE" }), /未执行清理/);
  assert.equal(formatExportError({ code: "INTERNAL_ERROR", message: "C:\\secret\\stack" }), "导出操作失败，请稍后重试");
});

test("audit events keep lifecycle evidence while redacting unapproved metadata", () => {
  const events = normalizeAuditEvents([
    {
      auditId: "audit-1",
      action: "PACKAGE_APPROVAL_BLOCKED",
      resourceType: "SKILL_VERSION",
      resourceId: "package-1",
      actorId: "admin-1",
      actorRole: "admin",
      requestId: "req-1",
      occurredAt: "2026-08-24T10:00:00Z",
      metadata: {
        skillId: "demo-skill",
        version: "2.0.0",
        status: "pending_review",
        qualityGateReasons: "OPTIMIZATION_DECISION_REQUIRED",
        prompt: "must-not-render",
        token: "must-not-render",
      },
    },
  ]);

  assert.deepEqual(auditMetadataEntries(events[0]), [
    ["Skill", "demo-skill"],
    ["版本", "2.0.0"],
    ["状态", "pending_review"],
    ["门禁原因", "OPTIMIZATION_DECISION_REQUIRED"],
  ]);
  assert.equal(events[0].metadata.prompt, undefined);
  assert.equal(events[0].metadata.token, undefined);
});
