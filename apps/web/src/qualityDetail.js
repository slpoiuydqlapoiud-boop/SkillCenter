import { normalizeRuntimeOperations } from "./operationsMetrics.js";
import { normalizeExecutionEnvironmentSnapshot, normalizeQualitySnapshot } from "./quality.js";

export function normalizeSkillQualityDetail(payload) {
  const value = payload?.data ?? payload ?? {};
  return {
    skillId: value.skillId || "",
    version: value.version || "",
    latestSnapshot: value.latestSnapshot ? normalizeQualitySnapshot(value.latestSnapshot) : null,
    snapshotHistory: Array.isArray(value.snapshotHistory)
      ? value.snapshotHistory.map((item) => normalizeQualitySnapshot(item)).filter((item) => item.snapshotId)
      : [],
    runtime: normalizeRuntimeOperations(value.runtime),
    availableVersions: Array.isArray(value.availableVersions) ? value.availableVersions.filter(Boolean) : [],
  };
}

export function normalizeQualityComparison(payload) {
  const value = payload?.data ?? payload ?? {};
  return {
    skillId: value.skillId || "",
    baselineVersion: value.baselineVersion || "",
    candidateVersion: value.candidateVersion || "",
    comparable: value.comparable === true,
    reasonCode: value.reasonCode || "NO_COMPARABLE_SNAPSHOT",
    reason: value.reason || "暂无可比较的同口径快照",
    baseline: normalizeComparisonMetric(value.baseline),
    candidate: normalizeComparisonMetric(value.candidate),
    delta: normalizeComparisonDelta(value.delta),
  };
}

export function normalizeQualityBenchmarks(payload) {
  const value = payload?.data ?? payload ?? [];
  if (!Array.isArray(value)) return [];
  return value.map((item, index) => {
    const normalized = {
      id: item?.benchmarkId || item?.id || `benchmark-${index}`,
      skillId: item?.skillId || "",
      baselineVersion: item?.baselineVersion || "",
      candidateVersion: item?.candidateVersion || "",
      window: item?.window || "24h",
      dataSource: item?.dataSource || "all",
      conclusion: item?.conclusion || "NOT_COMPARABLE",
      createdAt: item?.createdAt || "",
      comparison: normalizeQualityComparison(item?.comparison),
    };
    if (item?.runtimeId) normalized.runtimeId = String(item.runtimeId);
    if (item?.mcpServerId) normalized.mcpServerId = String(item.mcpServerId);
    if (item?.llmProviderId) normalized.llmProviderId = String(item.llmProviderId);
    if (item?.suiteId) normalized.suiteId = String(item.suiteId);
    if (item?.suiteVersion) normalized.suiteVersion = String(item.suiteVersion);
    const runtimeEnvironment = normalizeExecutionEnvironmentSnapshot(item?.runtimeEnvironment, "AGENT_RUNTIME");
    const mcpServerEnvironment = normalizeExecutionEnvironmentSnapshot(item?.mcpServerEnvironment, "MCP_SERVER");
    const llmProviderEnvironment = normalizeExecutionEnvironmentSnapshot(item?.llmProviderEnvironment, "LLM_PROVIDER");
    if (runtimeEnvironment) normalized.runtimeEnvironment = runtimeEnvironment;
    if (mcpServerEnvironment) normalized.mcpServerEnvironment = mcpServerEnvironment;
    if (llmProviderEnvironment) normalized.llmProviderEnvironment = llmProviderEnvironment;
    return normalized;
  });
}

export function normalizeQualitySuggestions(payload) {
  const value = payload?.data ?? payload ?? [];
  if (!Array.isArray(value)) return [];
  return value.map((item, index) => {
    const normalized = {
    id: item?.id || `suggestion-${index}`,
    severity: item?.severity || "INFO",
    category: item?.category || "QUALITY_DATA",
    title: item?.title || item?.category || "优化建议",
    evidence: Array.isArray(item?.evidence) ? item.evidence.filter(Boolean).map(String) : [],
    recommendedAction: item?.recommendedAction || "暂无建议动作",
    dispositionStatus: item?.dispositionStatus || "OPEN",
    dispositionNote: item?.dispositionNote || "",
    dispositionBy: item?.dispositionBy || "",
    dispositionAt: item?.dispositionAt || "",
    dispositionEvidenceStatus: item?.dispositionEvidenceStatus || (item?.dispositionEvidenceType ? "AVAILABLE" : "NOT_LINKED"),
    };
    if (item?.dispositionEvidenceType) normalized.dispositionEvidenceType = item.dispositionEvidenceType;
    if (item?.dispositionEvidenceId) normalized.dispositionEvidenceId = item.dispositionEvidenceId;
    return normalized;
  });
}

function normalizeComparisonMetric(value) {
  if (!value) return null;
  const normalized = {
    score: Number(value.score) || 0,
    staticScore: Number(value.staticScore) || 0,
    passRate: Number(value.passRate) || 0,
    successRate: Number(value.successRate) || 0,
    p95Ms: Number(value.p95Ms) || 0,
    totalCalls: Number(value.totalCalls) || 0,
    gateStatus: value.gateStatus || "UNKNOWN",
    dataSource: value.dataSource || "mock",
    suiteVersion: value.suiteVersion || "",
    ruleVersion: value.ruleVersion || "",
    runnerId: value.runnerId || "",
    evaluationProviderId: value.evaluationProviderId || "",
    measuredAt: value.measuredAt || "",
    suiteId: value.suiteId || "",
  };
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

function normalizeComparisonDelta(value) {
  if (!value) return null;
  return {
    score: Number(value.score) || 0,
    staticScore: Number(value.staticScore) || 0,
    passRate: Number(value.passRate) || 0,
    successRate: Number(value.successRate) || 0,
    p95Ms: Number(value.p95Ms) || 0,
    totalCalls: Number(value.totalCalls) || 0,
  };
}
