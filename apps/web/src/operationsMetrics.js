export const OPERATIONS_WINDOWS = [
  { value: "5m", label: "近 5 分钟" },
  { value: "15m", label: "近 15 分钟" },
  { value: "60m", label: "近 60 分钟" },
];

export const RUNTIME_OPERATIONS_WINDOWS = [
  { value: "5m", label: "近 5 分钟" },
  { value: "15m", label: "近 15 分钟" },
  { value: "60m", label: "近 60 分钟" },
  { value: "24h", label: "近 24 小时" },
  { value: "7d", label: "近 7 天" },
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

function normalizeLifecycleCounts(value) {
  const counts = value ?? {};
  return {
    skillCount: Number(counts.skillCount) || 0,
    versionCount: Number(counts.versionCount) || 0,
    releaseCount: Number(counts.releaseCount) || 0,
    scopeCount: Number(counts.scopeCount) || 0,
    relationCount: Number(counts.relationCount) || 0,
  };
}

export function normalizeLifecycleProjectionReconciliation(payload) {
  const value = payload?.data ?? payload ?? {};
  return {
    backend: typeof value.backend === "string" ? value.backend : "unknown",
    state: typeof value.state === "string" ? value.state : "UNKNOWN",
    reasonCode: typeof value.reasonCode === "string" ? value.reasonCode : "",
    revision: Number(value.revision) || 0,
    projectionAgeSeconds: value.projectionAgeSeconds == null ? null : Math.max(0, Number(value.projectionAgeSeconds) || 0),
    maxProjectionAgeSeconds: Math.max(0, Number(value.maxProjectionAgeSeconds) || 0),
    sourceCounts: normalizeLifecycleCounts(value.sourceCounts),
    projectedCounts: normalizeLifecycleCounts(value.projectedCounts),
    countDelta: normalizeLifecycleCounts(value.countDelta),
  };
}

export function normalizePlatformReadiness(payload) {
  const value = payload?.data ?? payload ?? {};
  const components = Array.isArray(value.components) ? value.components : [];
  return {
    overall: typeof value.overall === "string" ? value.overall : "UNKNOWN",
    scope: typeof value.scope === "string" ? value.scope : "PRODUCTION_HANDOFF",
    checkedAt: typeof value.checkedAt === "string" ? value.checkedAt : "",
    components: components.map((item) => ({
      componentId: typeof item?.componentId === "string" ? item.componentId : "UNKNOWN",
      status: typeof item?.status === "string" ? item.status : "UNKNOWN",
      reasonCode: typeof item?.reasonCode === "string" ? item.reasonCode : "",
      summary: typeof item?.summary === "string" ? item.summary : "",
    })),
    blockingReasonCodes: Array.isArray(value.blockingReasonCodes)
      ? value.blockingReasonCodes.filter((item) => typeof item === "string")
      : [],
  };
}

function normalizeWorkItemCounts(value) {
  if (!value || typeof value !== "object") return {};
  return Object.fromEntries(Object.entries(value)
    .filter(([key]) => typeof key === "string" && key.trim())
    .map(([key, count]) => [key, Math.max(0, Number(count) || 0)])
    .sort(([left], [right]) => left.localeCompare(right)));
}

export function normalizeOptimizationWorkItemHealth(payload) {
  const value = payload?.data ?? payload ?? {};
  const status = ["HEALTHY", "DEGRADED", "NOT_READY"].includes(value.status) ? value.status : "NOT_READY";
  return {
    status,
    reasonCode: typeof value.reasonCode === "string" ? value.reasonCode : "OPTIMIZATION_WORK_ITEM_HEALTH_UNAVAILABLE",
    evaluatedAt: typeof value.evaluatedAt === "string" ? value.evaluatedAt : "",
    staleThresholdSeconds: Math.max(60, Number(value.staleThresholdSeconds) || 604800),
    activeCount: Math.max(0, Number(value.activeCount) || 0),
    staleCount: Math.max(0, Number(value.staleCount) || 0),
    staleByStatus: normalizeWorkItemCounts(value.staleByStatus),
    staleByOwner: normalizeWorkItemCounts(value.staleByOwner),
    staleBySeverity: normalizeWorkItemCounts(value.staleBySeverity),
    staleItems: Array.isArray(value.staleItems) ? value.staleItems.slice(0, 100) : [],
  };
}

export function normalizeReleaseTargetProbe(payload) {
  const value = payload?.data ?? payload ?? {};
  const text = (candidate, fallback = "") => typeof candidate === "string"
    ? candidate.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 128)
    : fallback;
  const httpStatus = value.httpStatus == null ? null : Number(value.httpStatus);
  return {
    targetId: text(value.targetId, "release-target"),
    status: text(value.status, "UNKNOWN"),
    reasonCode: text(value.reasonCode),
    httpStatus: Number.isInteger(httpStatus) && httpStatus >= 100 && httpStatus <= 599 ? httpStatus : null,
    latencyMs: Math.max(0, Number(value.latencyMs) || 0),
    checkedAt: text(value.checkedAt),
  };
}

export function normalizeReleaseTargetProbes(payload) {
  const value = Array.isArray(payload?.data) ? payload.data : Array.isArray(payload) ? payload : [];
  return value.map((item) => normalizeReleaseTargetProbe(item));
}

export function normalizeProductionEvidence(payload) {
  const value = payload?.data ?? payload ?? [];
  if (!Array.isArray(value)) return [];
  return value.map((item) => ({
    evidenceId: typeof item?.evidenceId === "string" ? item.evidenceId : "UNKNOWN",
    status: typeof item?.status === "string" ? item.status : "MISSING",
    ownerUserId: typeof item?.ownerUserId === "string" ? item.ownerUserId : "",
    verifiedAt: typeof item?.verifiedAt === "string" ? item.verifiedAt : "",
    expiresAt: typeof item?.expiresAt === "string" ? item.expiresAt : "",
    evidenceRef: typeof item?.evidenceRef === "string" ? item.evidenceRef : "",
    summary: typeof item?.summary === "string" ? item.summary : "",
    revision: Math.max(0, Number(item?.revision) || 0),
    updatedBy: typeof item?.updatedBy === "string" ? item.updatedBy : "",
    updatedAt: typeof item?.updatedAt === "string" ? item.updatedAt : "",
  }));
}

export function normalizeRuntimeOperations(payload) {
  const value = payload?.data ?? payload ?? {};
  const filters = value.filters ?? {};
  const totals = value.totals ?? {};
  const latency = value.latency ?? {};
  const number = (item) => Number(item) || 0;
  const normalizedFilters = {
    skillId: filters.skillId || "",
    version: filters.version || "",
    teamId: filters.teamId || "",
  };
  if (filters.runtimeId) normalizedFilters.runtimeId = String(filters.runtimeId);
  if (filters.mcpServerId) normalizedFilters.mcpServerId = String(filters.mcpServerId);
  if (filters.llmProviderId) normalizedFilters.llmProviderId = String(filters.llmProviderId);
  return {
    window: value.window || "24h",
    generatedAt: value.generatedAt || "",
    dataSource: value.dataSource || "all",
    filters: normalizedFilters,
    totals: {
      total: number(totals.total),
      successes: number(totals.successes),
      failures: number(totals.failures),
      timeouts: number(totals.timeouts),
      cancellations: number(totals.cancellations),
      successRate: number(totals.successRate),
    },
    latency: {
      sampleCount: number(latency.sampleCount),
      p50Ms: number(latency.p50Ms),
      p95Ms: number(latency.p95Ms),
      maxMs: number(latency.maxMs),
    },
    errors: Array.isArray(value.errors) ? value.errors.map((item) => ({
      errorCode: typeof item?.errorCode === "string" ? item.errorCode : "UNKNOWN",
      count: number(item?.count),
      percentage: number(item?.percentage),
    })) : [],
    versionAdoption: Array.isArray(value.versionAdoption) ? value.versionAdoption.map((item) => ({
      version: typeof item?.version === "string" ? item.version : "unknown",
      calls: number(item?.calls),
      percentage: number(item?.percentage),
    })) : [],
    sources: Array.isArray(value.sources) ? value.sources.map((item) => ({
      dataSource: typeof item?.dataSource === "string" ? item.dataSource : "unknown",
      total: number(item?.total),
      successes: number(item?.successes),
      failures: number(item?.failures),
      timeouts: number(item?.timeouts),
      successRate: number(item?.successRate),
      p95Ms: number(item?.p95Ms),
    })) : [],
    trend: Array.isArray(value.trend) ? value.trend.map((item) => ({
      bucket: item?.bucket || "",
      calls: number(item?.calls),
      successes: number(item?.successes),
      failures: number(item?.failures),
      timeouts: number(item?.timeouts),
      successRate: number(item?.successRate),
      p95Ms: number(item?.p95Ms),
    })) : [],
  };
}

export function normalizeTraceObservations(payload) {
  const value = payload?.data ?? payload ?? [];
  if (!Array.isArray(value)) return [];
  return value.map((item) => {
    const normalized = {
    traceId: typeof item?.traceId === "string" ? item.traceId : "",
    spanId: typeof item?.spanId === "string" ? item.spanId : "",
    skillId: typeof item?.skillId === "string" ? item.skillId : "",
    version: typeof item?.version === "string" ? item.version : "",
    operation: typeof item?.operation === "string" ? item.operation : "skill.run",
    status: typeof item?.status === "string" ? item.status : "unknown",
    durationMs: Number(item?.durationMs) || 0,
    errorCode: typeof item?.errorCode === "string" ? item.errorCode : "",
    dataSource: typeof item?.dataSource === "string" ? item.dataSource : "unknown",
    occurredAt: typeof item?.occurredAt === "string" ? item.occurredAt : "",
    };
    if (item?.runtimeId) normalized.runtimeId = String(item.runtimeId);
    if (item?.mcpServerId) normalized.mcpServerId = String(item.mcpServerId);
    if (item?.llmProviderId) normalized.llmProviderId = String(item.llmProviderId);
    return normalized;
  });
}
