export const ANALYTICS_RANGES = [
  { value: "7d", label: "最近 7 天" },
  { value: "30d", label: "最近 30 天" },
  { value: "90d", label: "最近 90 天" },
  { value: "custom", label: "自定义范围" },
];

export function validateCustomRange(from, to) {
  if (!from || !to) return "请填写起始日期和结束日期";
  const pattern = /^\d{4}-\d{2}-\d{2}$/;
  if (!pattern.test(from) || !pattern.test(to)) return "日期格式必须为 YYYY-MM-DD";
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  if (!Number.isFinite(start) || !Number.isFinite(end)) return "日期格式无效";
  if (start > end) return "起始日期不能晚于结束日期";
  const days = Math.floor((end - start) / 86400000) + 1;
  if (days > 90) return "自定义范围不能超过 90 天";
  return "";
}

export function buildAnalyticsParams(range = "7d", from = "", to = "", filters = {}) {
  const params = { range };
  if (range === "custom") {
    params.from = from;
    params.to = to;
  }
  ["skillId", "teamId", "clientType"].forEach((key) => {
    if (filters[key] && filters[key] !== "all") params[key] = filters[key];
  });
  return params;
}

export function formatAnalyticsRange(range, from = "", to = "") {
  if (range === "custom") return from && to ? `${from} 至 ${to}` : "自定义范围";
  const item = ANALYTICS_RANGES.find((candidate) => candidate.value === range);
  return item?.label || ANALYTICS_RANGES[0].label;
}
