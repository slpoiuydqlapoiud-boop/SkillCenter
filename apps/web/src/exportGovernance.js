const STATUS_LABELS = {
  QUEUED: "排队中",
  RUNNING: "生成中",
  COMPLETED: "已完成",
  FAILED: "失败",
  EXPIRED: "已过期",
};

const ERROR_MESSAGES = {
  EXPORT_FORBIDDEN: "当前角色无权使用导出工作台",
  EXPORT_NOT_READY: "导出尚未完成，请稍后刷新",
  EXPORT_EXPIRED: "导出文件已过期，请重新生成",
  EXPORT_QUEUE_FULL: "导出任务较多，请稍后重试",
  RETENTION_POLICY_CONFLICT: "保留策略已被其他管理员更新，请刷新后重试",
  RETENTION_PREVIEW_EXPIRED: "保留策略预览已过期，请重新预览",
  RETENTION_EXECUTION_CONFLICT: "保留策略执行确认已失效，请重新预览",
  RETENTION_EVIDENCE_PROTECTION_UNAVAILABLE: "证据引用保护暂不可用，未执行清理；请检查存储后重新预览",
  RETENTION_PROTECTION_CONFLICT: "证据引用在预览后发生变化，未执行清理；请重新预览",
};

const AUDIT_METADATA_LABELS = [
  ["skillId", "Skill"],
  ["version", "版本"],
  ["status", "状态"],
  ["qualityGateReasons", "门禁原因"],
  ["evidenceType", "证据类型"],
  ["evidenceId", "证据 ID"],
  ["dataSource", "数据来源"],
  ["runtimeId", "Runtime"],
  ["mcpServerId", "MCP"],
  ["llmProviderId", "LLM"],
  ["workItemId", "工作项"],
  ["experimentId", "实验"],
  ["decision", "决策"],
];

export function canViewExportWorkbench(role) {
  return role === "admin" || role === "reviewer";
}

export function formatExportStatus(status) {
  return STATUS_LABELS[status] || "未知状态";
}

export function isExportTerminal(status) {
  return status === "COMPLETED" || status === "FAILED" || status === "EXPIRED";
}

export function validateRetentionForm(form = {}) {
  const errors = {};
  const audit = Number(form.auditRetentionDays);
  const invocation = Number(form.invocationRetentionDays);
  const installation = Number(form.installationRetentionDays);
  if (!Number.isInteger(audit) || audit < 365) errors.auditRetentionDays = "审计数据至少保留 365 天";
  if (!Number.isInteger(invocation) || invocation < 30) errors.invocationRetentionDays = "调用数据至少保留 30 天";
  if (!Number.isInteger(installation) || installation < 30) errors.installationRetentionDays = "安装数据至少保留 30 天";
  return errors;
}

export function normalizeRetentionPreview(preview = {}) {
  const count = (value) => Number.isFinite(Number(value)) ? Number(value) : 0;
  return {
    invocationEligibleCount: count(preview.invocationEligibleCount),
    installationEligibleCount: count(preview.installationEligibleCount),
    auditArchiveEligibleCount: count(preview.auditArchiveEligibleCount),
    runtimeSummaryEligibleCount: count(preview.runtimeSummaryEligibleCount),
    qualityEvidenceEligibleCount: count(preview.qualityEvidenceEligibleCount),
    benchmarkEligibleCount: count(preview.benchmarkEligibleCount),
    runnerExecutionEligibleCount: count(preview.runnerExecutionEligibleCount),
    compatibilityMatrixEligibleCount: count(preview.compatibilityMatrixEligibleCount),
    protectedEvaluationRunCount: count(preview.protectedEvaluationRunCount),
    protectedQualitySnapshotCount: count(preview.protectedQualitySnapshotCount),
    protectedBenchmarkCount: count(preview.protectedBenchmarkCount),
    protectedCompatibilityMatrixCount: count(preview.protectedCompatibilityMatrixCount),
    protectedReferenceCount: count(preview.protectedReferenceCount),
    protectionFingerprint: String(preview.protectionFingerprint || ""),
  };
}

export function normalizeAuditEvents(events = []) {
  return (Array.isArray(events) ? events : [])
    .filter((event) => event && typeof event === "object")
    .map((event) => ({
      auditId: String(event.auditId || ""),
      action: String(event.action || "UNKNOWN"),
      resourceType: String(event.resourceType || ""),
      resourceId: String(event.resourceId || ""),
      actorId: String(event.actorId || ""),
      actorRole: String(event.actorRole || ""),
      requestId: String(event.requestId || ""),
      occurredAt: String(event.occurredAt || ""),
      metadata: Object.fromEntries(AUDIT_METADATA_LABELS
        .map(([key]) => [key, event.metadata?.[key] === undefined ? undefined : String(event.metadata[key]).slice(0, 200)])
        .filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== "")),
    }))
    .sort((left, right) => right.occurredAt.localeCompare(left.occurredAt))
    .slice(0, 50);
}

export function auditMetadataEntries(event = {}) {
  return AUDIT_METADATA_LABELS
    .map(([key, label]) => [label, event.metadata?.[key]])
    .filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== "")
    .map(([label, value]) => [label, String(value)]);
}

export function formatExportError(error) {
  return ERROR_MESSAGES[error?.code] || "导出操作失败，请稍后重试";
}
