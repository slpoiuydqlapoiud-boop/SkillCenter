export const DISTRIBUTION_METHODS = [
  { value: "one-click", label: "一键安装", description: "生成一次性安装清单" },
  { value: "cli", label: "CLI 命令", description: "复制命令到客户端执行" },
  { value: "manual-zip", label: "ZIP 下载", description: "下载并校验本地 ZIP" },
];

export function buildPromptInstallInstruction(skill = {}) {
  const skillId = String(skill.id || skill.name || "当前").trim();
  const skillName = String(skill.name || skillId).trim();
  return `使用 ${skillId}，快速安装 ${skillName} Skill`;
}

export function triggerFileDownload(url, filename = "") {
  if (!url || typeof document === "undefined") return false;
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.rel = "noopener";
  document.body?.appendChild(link);
  link.click();
  link.remove?.();
  return true;
}

const TERMINAL_STATUSES = new Set(["installed", "failed", "removed"]);

export function isTerminalInstallationStatus(status) {
  return TERMINAL_STATUSES.has(status);
}

export function installationStatusTone(status) {
  if (status === "failed") return "high";
  if (status === "installed") return "success";
  if (status === "removed") return "neutral";
  return "warning";
}
