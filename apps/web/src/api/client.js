export class ApiError extends Error {
  constructor(message, { status = 0, code = "REQUEST_FAILED", details = [], requestId = "", retryAfterSeconds = null } = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.details = details;
    this.requestId = requestId;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export function createApiClient({ fetchImpl = globalThis.fetch, baseUrl = "", timeoutMs = 10000, actor = { userId: "local-user", role: "admin" } } = {}) {
  if (typeof fetchImpl !== "function") {
    throw new Error("fetch is required to create the API client");
  }

  function setActor(nextActor = {}) {
    actor = {
      userId: nextActor.userId || "local-user",
      role: nextActor.role || "admin",
      token: nextActor.token || "",
    };
  }

  async function request(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(`${baseUrl}${path}`, {
        ...options,
        signal: options.signal ?? controller.signal,
        headers: {
          Accept: "application/json",
          "X-User-Id": actor.userId,
          "X-User-Role": actor.role,
          ...(actor.token ? { Authorization: `Bearer ${actor.token}` } : {}),
          ...(options.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
          ...(options.headers ?? {}),
        },
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) {
        const code = payload?.error?.code || "REQUEST_FAILED";
        const retryAfterHeader = response.headers.get("Retry-After");
        const parsedRetryAfter = retryAfterHeader === null ? null : Number.parseInt(retryAfterHeader, 10);
        const retryAfterSeconds = Number.isFinite(parsedRetryAfter) ? Math.max(0, parsedRetryAfter) : null;
        throw new ApiError(messageForApiError(code, payload?.error?.message, response.status, retryAfterSeconds, payload?.error?.details), {
          status: response.status,
          code,
          details: payload?.error?.details,
          requestId: payload?.requestId,
          retryAfterSeconds,
        });
      }
      return payload;
    } catch (error) {
      if (error?.name === "AbortError") {
        throw new ApiError("请求超时，请稍后重试", { code: "REQUEST_TIMEOUT" });
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }

  async function login(username, password) {
    const response = await request("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
    const session = sessionFromResponse(response);
    setActor(session);
    return session;
  }

  async function loginAsGuest() {
    const response = await request("/api/v1/auth/guest", { method: "POST" });
    const session = sessionFromResponse(response);
    setActor(session);
    return session;
  }

  async function logout() {
    try { await request("/api/v1/auth/logout", { method: "POST" }); } finally { setActor(); }
  }

  return { request, setActor, login, loginAsGuest, logout };
}

function sessionFromResponse(response) {
  const actor = response?.data?.actor;
  if (!response?.data?.token || !actor?.userId || !actor?.role) {
    throw new ApiError("登录响应无效", { code: "AUTH_RESPONSE_INVALID" });
  }
  return {
    userId: actor.userId,
    role: actor.role,
    token: response.data.token,
    displayName: actor.userId === "developer-user" ? "普通开发者" : actor.userId,
    expiresAt: response.data.expiresAt,
  };
}

function messageForApiError(code, serverMessage, status, retryAfterSeconds, details = []) {
  if (code === "RATE_LIMITED") {
    return retryAfterSeconds === null
      ? "请求过于频繁，请稍后重试"
      : `请求过于频繁，请在 ${retryAfterSeconds} 秒后重试`;
  }
  if (code === "CSRF_ORIGIN_REJECTED") {
    return "请求来源未被允许，请刷新页面后重试";
  }
  if (code === "PACKAGE_VALIDATION_FAILED") {
    const validationDetails = formatValidationDetails(details);
    if (validationDetails) return `Skill 包校验失败：${validationDetails}`;
  }
  if (code === "QUALITY_GATE_BLOCKED") {
    const optimizationGateMessage = formatOptimizationGateMessage(details);
    if (optimizationGateMessage) return optimizationGateMessage;
    const gateDetails = formatValidationDetails(details);
    if (gateDetails) return `质量门禁未通过：${gateDetails}`;
    return "质量门禁未通过，请完成评测并处理阻断原因后再发布";
  }
  if (code === "REVIEW_STATE_CONFLICT") {
    return "审核状态已变化或需要另一名审核人，请刷新审核队列后重试";
  }
  if (code === "SKILL_VERSION_CONFLICT") {
    return "Skill 版本已存在或必须高于最新版本，请提升版本号后重新上传";
  }
  if (code === "RUNNER_VERSION_NOT_ALLOWED") {
    return "只能对已发布的 Skill 版本执行评测，请选择已发布版本";
  }
  if (code === "RUNNER_SCENARIO_INVALID") {
    return "Runner 场景不在允许范围内，请选择受控 Mock 场景";
  }
  return serverMessage || `Request failed with status ${status}`;
}

function formatValidationDetails(details) {
  if (!Array.isArray(details)) return "";
  return details
    .map((detail) => {
      if (typeof detail === "string") return detail.trim();
      if (!detail || typeof detail !== "object") return "";
      const reason = typeof detail.reason === "string" ? detail.reason.trim() : "";
      const path = typeof detail.path === "string" ? detail.path.trim() : "";
      if (!reason) return path;
      return path ? `${path}：${reason}` : reason;
    })
    .filter(Boolean)
    .join("；");
}

function formatOptimizationGateMessage(details) {
  const reasons = (Array.isArray(details) ? details : [])
    .map((detail) => typeof detail === "string" ? detail.trim() : detail?.reason)
    .filter((reason) => typeof reason === "string" && reason.startsWith("OPTIMIZATION_"));
  if (!reasons.length) return "";
  const labels = reasons.map((reason) => ({
    OPTIMIZATION_EXPERIMENT_INCOMPLETE: "实验仍在排队或运行中",
    OPTIMIZATION_EXPERIMENT_FAILED: "最新实验失败",
    OPTIMIZATION_EXPERIMENT_CANCELLED: "最新实验已取消",
    OPTIMIZATION_DECISION_REQUIRED: "实验尚未生成决策",
    OPTIMIZATION_DECISION_BLOCKED: "实验决策不允许发布",
  }[reason] || reason));
  return `优化实验门禁未通过：${labels.join("；")}。请前往质量管理中心处理实验后再发布`;
}
