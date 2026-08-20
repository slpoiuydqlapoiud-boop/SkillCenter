export function uploadValidationError(result) {
  if (!result || result.valid !== false) return "";

  const errors = Array.isArray(result.errors)
    ? result.errors.filter((error) => typeof error === "string" && error.trim())
    : [];
  return errors.length > 0
    ? errors.join("；")
    : "Skill ZIP 校验失败，请检查包结构和 skill.json";
}
