export const OPERATIONS_WINDOWS = [
  { value: "5m", label: "近 5 分钟" },
  { value: "15m", label: "近 15 分钟" },
  { value: "60m", label: "近 60 分钟" },
];

export function normalizeOperationsMetrics(payload) {
  const value = payload?.data ?? payload ?? {};
  const health = value.health ?? {};
  const requests = value.requests ?? {};
  const latency = value.latency ?? {};
  const securityEvents = Object.entries(value.securityEvents ?? {})
    .map(([code, count]) => ({ code, count: Number(count) || 0 }))
    .filter((item) => item.count > 0)
    .sort((left, right) => right.count - left.count || left.code.localeCompare(right.code));
  return {
    window: value.window || "15m",
    generatedAt: value.generatedAt || "",
    health: {
      status: health.status || "UNKNOWN",
      packageStorage: health.packageStorage || "UNKNOWN",
      invocationPersistence: health.invocationPersistence || "UNKNOWN",
      metricsPersistence: health.metricsPersistence || "UNKNOWN",
    },
    requests: {
      total: Number(requests.total) || 0,
      successes: Number(requests.successes) || 0,
      clientErrors: Number(requests.clientErrors) || 0,
      serverErrors: Number(requests.serverErrors) || 0,
    },
    latency: {
      p50Ms: Number(latency.p50Ms) || 0,
      p95Ms: Number(latency.p95Ms) || 0,
      maxMs: Number(latency.maxMs) || 0,
    },
    securityEvents,
  };
}

export function normalizeOperationsAlerts(payload) {
  const value = Array.isArray(payload?.data) ? payload.data : Array.isArray(payload) ? payload : [];
  return {
    alerts: value.map((item) => ({
      rule: typeof item?.rule === "string" ? item.rule : "UNKNOWN",
      eventCode: typeof item?.eventCode === "string" ? item.eventCode : null,
      status: item?.status === "ACTIVE" ? "ACTIVE" : "RESOLVED",
      currentValue: Number(item?.currentValue) || 0,
      threshold: Number(item?.threshold) || 0,
      unit: typeof item?.unit === "string" ? item.unit : "",
      firstTriggeredAt: item?.firstTriggeredAt || "",
      lastEvaluatedAt: item?.lastEvaluatedAt || "",
    })),
  };
}
