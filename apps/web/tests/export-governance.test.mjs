import test from "node:test";
import assert from "node:assert/strict";
import {
  canViewExportWorkbench,
  formatExportError,
  formatExportStatus,
  isExportTerminal,
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

test("export errors never expose internal messages", () => {
  assert.equal(formatExportError({ code: "EXPORT_EXPIRED" }), "导出文件已过期，请重新生成");
  assert.equal(formatExportError({ code: "INTERNAL_ERROR", message: "C:\\secret\\stack" }), "导出操作失败，请稍后重试");
});
