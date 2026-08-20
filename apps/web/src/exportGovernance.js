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
};

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

export function formatExportError(error) {
  return ERROR_MESSAGES[error?.code] || "导出操作失败，请稍后重试";
}
