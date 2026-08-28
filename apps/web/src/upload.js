export function uploadValidationError(result) {
  if (!result || result.valid !== false) return "";

  const errors = Array.isArray(result.errors)
    ? result.errors.filter((error) => typeof error === "string" && error.trim())
    : [];
  return errors.length > 0
    ? errors.join("；")
    : "Skill ZIP 校验失败，请检查包结构和 skill.json";
}

export function uploadSecurityStatusLabel(result) {
  return ({
    PASSED: "本地安全扫描通过",
    BLOCKED: "本地安全扫描阻断",
    NOT_SCANNED: "本地安全扫描未完成",
  })[result?.securityStatus] || "";
}
