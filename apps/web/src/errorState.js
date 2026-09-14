export const ERROR_STATE_COPY = Object.freeze({
  forbidden: Object.freeze({
    title: "无权访问",
    description: "你没有权限访问此资源。",
    action: "返回技能市场",
    retry: false,
  }),
  "not-found": Object.freeze({
    title: "页面不存在",
    description: "你访问的页面不存在或已被移除。",
    action: "返回技能市场",
    retry: false,
  }),
  "server-error": Object.freeze({
    title: "服务暂时不可用",
    description: "服务处理请求时遇到问题，请稍后重试。",
    action: "返回技能市场",
    retry: true,
  }),
  maintenance: Object.freeze({
    title: "系统维护中",
    description: "平台正在维护，请稍后重试。",
    action: "返回技能市场",
    retry: true,
  }),
});

export function normalizeErrorState(error, fallback = "server-error") {
  const code = String(error?.code || "").toUpperCase();
  const status = Number(error?.status);
  if (status === 403 || code === "FORBIDDEN") return "forbidden";
  if (status === 404 || code === "NOT_FOUND" || code.endsWith("_NOT_FOUND")) return "not-found";
  if (status === 503 || code === "SERVICE_UNAVAILABLE" || code === "REQUEST_TIMEOUT") return "maintenance";
  if (status >= 500 || code === "REQUEST_FAILED") return "server-error";
  return Object.hasOwn(ERROR_STATE_COPY, fallback) ? fallback : "server-error";
}

export function errorStateCopy(state) {
  return ERROR_STATE_COPY[state] || ERROR_STATE_COPY["server-error"];
}
