export function normalizeQualityRules(payload) {
  const value = payload?.data ?? payload ?? {};
  return {
    id: value.id || "default",
    version: value.version || "quality-v1",
    minScore: Number.isFinite(Number(value.minScore)) ? Number(value.minScore) : 80,
    minPassRate: Number.isFinite(Number(value.minPassRate)) ? Number(value.minPassRate) : 0.8,
    minStaticScore: Number.isFinite(Number(value.minStaticScore)) ? Number(value.minStaticScore) : 90,
  };
}

export function normalizeOptimizationSuggestionThresholds(payload) {
  const value = payload?.data ?? payload ?? {};
  const numberOr = (input, fallback) => Number.isFinite(Number(input)) ? Number(input) : fallback;
  return {
    minSuccessRatePercent: numberOr(value.minSuccessRatePercent, 95),
    maxP95Ms: Math.max(1, Math.round(numberOr(value.maxP95Ms, 1000))),
    minRuntimeSamples: Math.max(1, Math.round(numberOr(value.minRuntimeSamples, 5))),
  };
}

const EXECUTION_ENVIRONMENT_KINDS = new Set(["AGENT_RUNTIME", "MCP_SERVER", "LLM_PROVIDER"]);
const EXECUTION_ENVIRONMENT_STATUSES = new Set(["ACTIVE", "DEGRADED", "DISABLED"]);
const BOUNDED_IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;

export function normalizeExecutionEnvironmentSnapshot(value, expectedKind = "") {
  if (!value || typeof value !== "object") return null;
  const kind = typeof value.kind === "string" ? value.kind.trim().toUpperCase() : String(expectedKind || "").trim().toUpperCase();
  const environmentId = typeof value.environmentId === "string" ? value.environmentId.trim() : "";
  const version = typeof value.version === "string" ? value.version.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 128) : "";
  const status = typeof value.status === "string" ? value.status.trim().toUpperCase() : "";
  const revision = Number(value.revision);
  if (!EXECUTION_ENVIRONMENT_KINDS.has(kind) || !BOUNDED_IDENTIFIER.test(environmentId)
    || !version || !EXECUTION_ENVIRONMENT_STATUSES.has(status)
    || !Number.isInteger(revision) || revision < 1) return null;
  return { kind, environmentId, version, revision, status };
}

export function formatExecutionEnvironmentSnapshot(value, fallbackId = "") {
  const normalized = normalizeExecutionEnvironmentSnapshot(value);
  const fallback = typeof fallbackId === "string" && BOUNDED_IDENTIFIER.test(fallbackId.trim()) ? fallbackId.trim() : "";
  if (!normalized) return fallback;
  return `${normalized.environmentId} · ${normalized.version} · rev ${normalized.revision} · ${normalized.status}`;
}

export function normalizeQualitySnapshot(payload) {
  const value = payload?.data ?? payload ?? {};
  const normalized = {
    score: Number(value.score) || 0,
    staticScore: Number(value.staticScore) || 0,
    passRate: Number(value.passRate) || 0,
    gateStatus: value.gateStatus === "PASSED" ? "PASSED" : "BLOCKED",
    gateReasons: Array.isArray(value.gateReasons) ? value.gateReasons : [],
    dataSource: value.dataSource || "mock",
    ruleVersion: value.ruleVersion || "quality-v1",
  };
  if (value.snapshotId) normalized.snapshotId = String(value.snapshotId);
  if (value.skillId) normalized.skillId = String(value.skillId);
  if (value.skillVersion) normalized.skillVersion = String(value.skillVersion);
  if (value.suiteId) normalized.suiteId = String(value.suiteId);
  if (value.suiteVersion) normalized.suiteVersion = String(value.suiteVersion);
  if (value.measuredAt) normalized.measuredAt = String(value.measuredAt);
  if (value.runtimeId) normalized.runtimeId = String(value.runtimeId);
  if (value.mcpServerId) normalized.mcpServerId = String(value.mcpServerId);
  if (value.llmProviderId) normalized.llmProviderId = String(value.llmProviderId);
  const runtimeEnvironment = normalizeExecutionEnvironmentSnapshot(value.runtimeEnvironment, "AGENT_RUNTIME");
  const mcpServerEnvironment = normalizeExecutionEnvironmentSnapshot(value.mcpServerEnvironment, "MCP_SERVER");
  const llmProviderEnvironment = normalizeExecutionEnvironmentSnapshot(value.llmProviderEnvironment, "LLM_PROVIDER");
  if (runtimeEnvironment) normalized.runtimeEnvironment = runtimeEnvironment;
  if (mcpServerEnvironment) normalized.mcpServerEnvironment = mcpServerEnvironment;
  if (llmProviderEnvironment) normalized.llmProviderEnvironment = llmProviderEnvironment;
  return normalized;
}

export function normalizeQualityEvaluationRun(payload) {
  const value = payload?.data ?? payload ?? {};
  const normalized = { ...value };
  const runtimeEnvironment = normalizeExecutionEnvironmentSnapshot(value.runtimeEnvironment, "AGENT_RUNTIME");
  const mcpServerEnvironment = normalizeExecutionEnvironmentSnapshot(value.mcpServerEnvironment, "MCP_SERVER");
  const llmProviderEnvironment = normalizeExecutionEnvironmentSnapshot(value.llmProviderEnvironment, "LLM_PROVIDER");
  normalized.runtimeEnvironment = runtimeEnvironment;
  normalized.mcpServerEnvironment = mcpServerEnvironment;
  normalized.llmProviderEnvironment = llmProviderEnvironment;
  return normalized;
}

export function normalizeSkillExecutionRecord(payload) {
  const value = payload?.data ?? payload ?? {};
  const normalized = { ...value };
  const runtimeEnvironment = normalizeExecutionEnvironmentSnapshot(value.runtimeEnvironment, "AGENT_RUNTIME");
  const mcpServerEnvironment = normalizeExecutionEnvironmentSnapshot(value.mcpServerEnvironment, "MCP_SERVER");
  const llmProviderEnvironment = normalizeExecutionEnvironmentSnapshot(value.llmProviderEnvironment, "LLM_PROVIDER");
  normalized.runtimeEnvironment = runtimeEnvironment;
  normalized.mcpServerEnvironment = mcpServerEnvironment;
  normalized.llmProviderEnvironment = llmProviderEnvironment;
  return normalized;
}
