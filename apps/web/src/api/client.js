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

  return { request, setActor };
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
