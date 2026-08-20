export function validateGovernanceForm(form = {}, required = []) {
  const errors = {};
  required.forEach((field) => {
    if (form[field] === undefined || form[field] === null || String(form[field]).trim() === "") errors[field] = "此字段必填";
  });
  if (form.sortOrder !== undefined && (!Number.isInteger(Number(form.sortOrder)) || Number(form.sortOrder) < 0)) errors.sortOrder = "排序必须是非负整数";
  if (form.pageSizeOptions && (!Array.isArray(form.pageSizeOptions) || form.pageSizeOptions.some((item) => Number(item) <= 0))) errors.pageSizeOptions = "分页选项必须为正整数";
  return errors;
}

export function upsertById(items, value, idField = "id") {
  const index = items.findIndex((item) => item?.[idField] === value?.[idField]);
  if (index < 0) return [...items, value];
  return items.map((item, itemIndex) => itemIndex === index ? value : item);
}

export function removeById(items, id, idField = "id") {
  return items.filter((item) => item?.[idField] !== id);
}

export function formatConfigError(error) {
  if (!error) return "配置操作失败";
  if (error.code === "CONFIG_CONFLICT") return "编码已存在或配置已被其他管理员更新";
  if (error.code === "COLLECTION_NOT_FOUND") return "合集不存在、已停用或当前用户无权访问";
  if (error.code === "SKILL_NOT_FOUND") return "引用的 Skill 不存在或尚未发布";
  return error.message || "配置操作失败";
}
