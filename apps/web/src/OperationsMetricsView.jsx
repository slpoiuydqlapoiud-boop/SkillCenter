import { useEffect, useState } from "react";
import { OPERATIONS_WINDOWS, RUNTIME_OPERATIONS_WINDOWS, normalizeLifecycleProjectionReconciliation, normalizeOperationsAlerts, normalizeOperationsMetrics, normalizeOptimizationWorkItemHealth, normalizePlatformReadiness, normalizeProductionEvidence, normalizeReleaseTargetProbe, normalizeReleaseTargetProbes, normalizeRuntimeOperations, normalizeTraceObservations } from "./operationsMetrics.js";
import "./operationsMetrics.css";

function healthLabel(value) {
  return value === "UP" ? "正常" : value === "UNKNOWN" ? "未知" : "需关注";
}

function alertLabel(alert) {
  if (alert.rule === "SKILL_LIFECYCLE_PROJECTION") return `Skill 生命周期投影 · ${alert.eventCode || "状态"}`;
  if (alert.rule === "OPTIMIZATION_WORK_ITEM_STALENESS") return "优化工作项滞留";
  if (alert.rule === "PRODUCTION_HANDOFF_EVIDENCE") return "生产交付证据";
  if (alert.eventCode) return `${alert.eventCode} 安全事件`;
  if (alert.rule === "P95_LATENCY") return "P95 延迟";
  if (alert.rule === "SERVER_ERROR_RATE") return "5xx 错误率";
  return alert.rule;
}

function alertValue(alert) {
  if (alert.unit === "ratio") return `${(alert.currentValue * 100).toFixed(1)}% / ${(alert.threshold * 100).toFixed(1)}%`;
  if (alert.unit === "state") return alert.currentValue >= alert.threshold ? "状态异常" : "状态正常";
  return `${alert.currentValue}${alert.unit ? ` ${alert.unit}` : ""} / ${alert.threshold}${alert.unit ? ` ${alert.unit}` : ""}`;
}

function sourceLabel(value) {
  return value === "mock" ? "Mock 受控运行" : value === "production" ? "生产调用" : "全部来源";
}

function traceStatusLabel(value) {
  return value === "timeout" ? "超时" : value === "failure" ? "失败" : value === "cancelled" ? "已取消" : "成功";
}

function lifecycleStateLabel(value) {
  return ({ HEALTHY: "健康", LIVE_SOURCE: "实时事实源", DRIFTED: "存在漂移", STALE: "投影过期", NOT_IMPORTED: "尚未导入", NOT_READY: "未就绪" })[value] || "未知";
}

function lifecycleAgeLabel(value) {
  return value == null ? "实时读取" : `${value} 秒`;
}

function lifecycleDeltaLabel(delta) {
  const entries = [["技能", delta.skillCount], ["版本", delta.versionCount], ["发布", delta.releaseCount], ["范围", delta.scopeCount], ["关系", delta.relationCount]]
    .filter(([, value]) => value !== 0)
    .map(([label, value]) => `${label}${value > 0 ? "+" : ""}${value}`);
  return entries.length ? entries.join("、") : "无差异";
}

export function OperationsMetricsView({ api, role, initialSkillId = "", initialSkillVersion = "", initialEnvironmentFilters = {}, initialTraceFilters = {}, onNavigate }) {
  const [window, setWindow] = useState("15m");
  const [metrics, setMetrics] = useState(() => normalizeOperationsMetrics(null));
  const [alerts, setAlerts] = useState(() => normalizeOperationsAlerts(null));
  const [lifecycle, setLifecycle] = useState(() => normalizeLifecycleProjectionReconciliation(null));
  const [platformReadiness, setPlatformReadiness] = useState(() => normalizePlatformReadiness(null));
  const [optimizationWorkItemHealth, setOptimizationWorkItemHealth] = useState(() => normalizeOptimizationWorkItemHealth(null));
  const [releaseTargetProbe, setReleaseTargetProbe] = useState(() => normalizeReleaseTargetProbe(null));
  const [releaseTargetProbes, setReleaseTargetProbes] = useState(() => normalizeReleaseTargetProbes(null));
  const [releaseTargetProbeLoading, setReleaseTargetProbeLoading] = useState(false);
  const [releaseTargetProbeError, setReleaseTargetProbeError] = useState("");
  const [productionEvidence, setProductionEvidence] = useState(() => normalizeProductionEvidence(null));
  const [productionEvidenceDrafts, setProductionEvidenceDrafts] = useState({});
  const [productionEvidenceError, setProductionEvidenceError] = useState("");
  const [lifecycleError, setLifecycleError] = useState("");
  const [runtimeWindow, setRuntimeWindow] = useState("24h");
  const [runtimeSkillId, setRuntimeSkillId] = useState(initialSkillId || "");
  const [runtimeVersion, setRuntimeVersion] = useState(initialSkillVersion || "");
  const [runtimeTeamId, setRuntimeTeamId] = useState("");
  const [runtimeSource, setRuntimeSource] = useState(initialEnvironmentFilters.dataSource || "all");
  const [runtimeRuntimeId, setRuntimeRuntimeId] = useState(initialEnvironmentFilters.runtimeId || "");
  const [runtimeMcpServerId, setRuntimeMcpServerId] = useState(initialEnvironmentFilters.mcpServerId || "");
  const [runtimeLlmProviderId, setRuntimeLlmProviderId] = useState(initialEnvironmentFilters.llmProviderId || "");
  const [traceId, setTraceId] = useState(initialTraceFilters.traceId || "");
  const [traceStatus, setTraceStatus] = useState(initialTraceFilters.status || "");
  const [runtime, setRuntime] = useState(() => normalizeRuntimeOperations(null));
  const [traces, setTraces] = useState([]);
  const [loading, setLoading] = useState(true);
  const [runtimeLoading, setRuntimeLoading] = useState(true);
  const [traceLoading, setTraceLoading] = useState(true);
  const [error, setError] = useState("");
  const [runtimeError, setRuntimeError] = useState("");
  const [traceError, setTraceError] = useState("");

  function updateEvidenceDraft(evidenceId, field, value) {
    setProductionEvidenceDrafts((current) => ({
      ...current,
      [evidenceId]: { ...(current[evidenceId] || {}), [field]: value },
    }));
  }

  async function saveProductionEvidence(item) {
    if (typeof api.updateProductionEvidence !== "function") return;
    const draft = productionEvidenceDrafts[item.evidenceId] || item;
    setProductionEvidenceError("");
    try {
      const response = await api.updateProductionEvidence(item.evidenceId, {
        status: draft.status || item.status,
        ownerUserId: draft.ownerUserId || "",
        expiresAt: draft.expiresAt || null,
        evidenceRef: draft.evidenceRef || "",
        summary: draft.summary || "",
        expectedRevision: item.revision,
      });
      const updated = normalizeProductionEvidence(response)[0];
      if (updated?.evidenceId) {
        setProductionEvidence((current) => current.map((value) => value.evidenceId === updated.evidenceId ? updated : value));
        setProductionEvidenceDrafts((current) => ({ ...current, [updated.evidenceId]: updated }));
      }
    } catch (saveError) {
      setProductionEvidenceError(saveError?.message || "生产证据保存失败");
    }
  }

  async function probeReleaseTarget() {
    if (typeof api.probeReleaseTarget !== "function") return;
    setReleaseTargetProbeLoading(true);
    setReleaseTargetProbeError("");
    try {
      const response = await api.probeReleaseTarget();
      setReleaseTargetProbe(normalizeReleaseTargetProbe(response));
      if (typeof api.getPlatformReadiness === "function") {
        const readinessResponse = await api.getPlatformReadiness();
        setPlatformReadiness(normalizePlatformReadiness(readinessResponse));
      }
      if (typeof api.listReleaseTargetProbes === "function") {
        const historyResponse = await api.listReleaseTargetProbes(20);
        setReleaseTargetProbes(normalizeReleaseTargetProbes(historyResponse));
      }
    } catch (probeError) {
      setReleaseTargetProbeError(probeError?.message || "发布目标连通性探测失败");
    } finally {
      setReleaseTargetProbeLoading(false);
    }
  }

  useEffect(() => {
    if (role !== "admin") return;
    let active = true;
    setLoading(true);
    setError("");
    setLifecycleError("");
    setProductionEvidenceError("");
    const lifecycleRequest = typeof api.getSkillLifecycleProjectionReconciliation === "function"
      ? Promise.resolve()
        .then(() => api.getSkillLifecycleProjectionReconciliation())
        .then((response) => ({ response, error: "" }))
        .catch(() => ({ response: { data: null }, error: "生命周期观测暂不可用" }))
      : Promise.resolve({ response: { data: null }, error: "" });
    const platformReadinessRequest = typeof api.getPlatformReadiness === "function"
      ? Promise.resolve().then(() => api.getPlatformReadiness()).catch(() => ({ data: null }))
      : Promise.resolve({ data: null });
    const productionEvidenceRequest = typeof api.listProductionEvidence === "function"
      ? Promise.resolve().then(() => api.listProductionEvidence()).catch(() => ({ data: null }))
      : Promise.resolve({ data: null });
    const releaseTargetHistoryRequest = typeof api.listReleaseTargetProbes === "function"
      ? Promise.resolve().then(() => api.listReleaseTargetProbes(20)).catch(() => ({ data: [] }))
      : Promise.resolve({ data: [] });
    const optimizationWorkItemHealthRequest = typeof api.getOptimizationWorkItemHealth === "function"
      ? Promise.resolve().then(() => api.getOptimizationWorkItemHealth()).catch(() => ({ data: null }))
      : Promise.resolve({ data: null });
    Promise.all([api.getOperationsMetrics(window), api.getOperationsAlerts(window), lifecycleRequest, platformReadinessRequest, productionEvidenceRequest, releaseTargetHistoryRequest, optimizationWorkItemHealthRequest])
      .then(([metricsResponse, alertsResponse, lifecycleResult, platformReadinessResponse, productionEvidenceResponse, releaseTargetHistoryResponse, optimizationWorkItemHealthResponse]) => {
        if (active) {
          setMetrics(normalizeOperationsMetrics(metricsResponse));
          setAlerts(normalizeOperationsAlerts(alertsResponse));
          setLifecycle(normalizeLifecycleProjectionReconciliation(lifecycleResult.response));
          setPlatformReadiness(normalizePlatformReadiness(platformReadinessResponse));
          const normalizedEvidence = normalizeProductionEvidence(productionEvidenceResponse);
          setProductionEvidence(normalizedEvidence);
          setProductionEvidenceDrafts(Object.fromEntries(normalizedEvidence.map((item) => [item.evidenceId, item])));
          setReleaseTargetProbes(normalizeReleaseTargetProbes(releaseTargetHistoryResponse));
          setOptimizationWorkItemHealth(normalizeOptimizationWorkItemHealth(optimizationWorkItemHealthResponse));
          setLifecycleError(lifecycleResult.error);
        }
      })
      .catch((loadError) => { if (active) setError(loadError.message || "运行指标加载失败"); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [api, role, window]);

  useEffect(() => {
    if (role !== "admin") return;
    let active = true;
    setRuntimeLoading(true);
    setRuntimeError("");
    api.getSkillRuntimeMetrics({ window: runtimeWindow, skillId: runtimeSkillId, version: runtimeVersion, teamId: runtimeTeamId, dataSource: runtimeSource, runtimeId: runtimeRuntimeId, mcpServerId: runtimeMcpServerId, llmProviderId: runtimeLlmProviderId })
      .then((response) => { if (active) setRuntime(normalizeRuntimeOperations(response)); })
      .catch((loadError) => { if (active) setRuntimeError(loadError.message || "Skill 运行指标加载失败"); })
      .finally(() => { if (active) setRuntimeLoading(false); });
    return () => { active = false; };
  }, [api, role, runtimeWindow, runtimeSkillId, runtimeVersion, runtimeTeamId, runtimeSource, runtimeRuntimeId, runtimeMcpServerId, runtimeLlmProviderId]);

  useEffect(() => {
    if (role !== "admin") return;
    let active = true;
    setTraceLoading(true);
    setTraceError("");
    api.getTraces({ window: runtimeWindow, skillId: runtimeSkillId, version: runtimeVersion, traceId, status: traceStatus, dataSource: runtimeSource, runtimeId: runtimeRuntimeId, mcpServerId: runtimeMcpServerId, llmProviderId: runtimeLlmProviderId })
      .then((response) => { if (active) setTraces(normalizeTraceObservations(response)); })
      .catch((loadError) => { if (active) setTraceError(loadError.message || "Trace 查询失败"); })
      .finally(() => { if (active) setTraceLoading(false); });
    return () => { active = false; };
  }, [api, role, runtimeWindow, runtimeSkillId, runtimeVersion, traceId, traceStatus, runtimeSource, runtimeRuntimeId, runtimeMcpServerId, runtimeLlmProviderId]);

  if (role !== "admin") return <main className="content api-state error-state">当前角色无权查看运行监控</main>;
  if (loading) return <main className="content api-state">正在加载运行指标…</main>;
  const visibleTraces = traceStatus ? traces.filter((item) => item.status === traceStatus) : traces.filter((item) => item.status === "failure" || item.status === "timeout");
  const traceEmptyLabel = traceStatus ? `${traceStatusLabel(traceStatus)} Trace` : "失败或超时 Trace";
  const tracePanelTitle = traceStatus ? "Trace 查询结果" : "Trace 失败定位";
  const tracePanelDescription = traceStatus ? "按当前状态展示脱敏 Trace 元数据。" : "仅展示失败和超时 Trace 元数据，不包含 Prompt、输入输出或工具参数。";

  const releaseTargetFailureCount = releaseTargetProbes.filter((item) => !["REACHABLE", "SKIPPED"].includes(item.status)).length;
  const releaseTargetTimeoutCount = releaseTargetProbes.filter((item) => item.status === "TIMEOUT").length;
  const workItemBreakdown = (values) => Object.entries(values).map(([key, value]) => `${key} ${value}`).join("、") || "无";

  return <main className="content operations-content">
    <div className="page-title-row"><div><h1>运行运营中心</h1><p>聚合 Skill 运行摘要，区分 Mock 受控运行与生产调用；不保存业务正文、凭据或用户明细。</p><p className="runtime-environment-context">当前执行环境：Runtime {runtimeRuntimeId || "全部"}{runtimeMcpServerId ? ` · MCP ${runtimeMcpServerId}` : ""}{runtimeLlmProviderId ? ` · LLM ${runtimeLlmProviderId}` : ""}</p></div><select value={runtimeWindow} onChange={(event) => setRuntimeWindow(event.target.value)} aria-label="Skill 运行时间窗口">{RUNTIME_OPERATIONS_WINDOWS.map((item) => <option value={item.value} key={item.value}>{item.label}</option>)}</select></div>
    <section className={`panel platform-readiness platform-readiness-${String(platformReadiness.overall).toLowerCase()}`} data-testid="platform-readiness"><div className="section-heading"><div><h2>生产交付准入</h2><p>统一汇总持久化、Provider、安全门与外部交付证据；证据台账仅在显式保存后更新。</p></div><div><strong>{platformReadiness.overall}{platformReadiness.scope ? ` · ${platformReadiness.scope}` : ""}</strong>{typeof api.probeReleaseTarget === "function" && <button type="button" className="secondary-button" data-testid="release-target-probe" onClick={probeReleaseTarget} disabled={releaseTargetProbeLoading}>{releaseTargetProbeLoading ? "探测中…" : "探测发布目标"}</button>}</div></div>{platformReadiness.components.length ? <div className="platform-readiness-list">{platformReadiness.components.map((component) => <div key={component.componentId}><span>{component.componentId}</span><strong>{component.status}</strong><small>{component.reasonCode}{component.summary ? ` · ${component.summary}` : ""}</small></div>)}</div> : <div className="empty-state">平台级 readiness 接口暂不可用。</div>}{releaseTargetProbe.status !== "UNKNOWN" && <small data-testid="release-target-probe-result" className="lifecycle-health-reason">发布目标探测：{releaseTargetProbe.status} · {releaseTargetProbe.reasonCode}{releaseTargetProbe.httpStatus ? ` · HTTP ${releaseTargetProbe.httpStatus}` : ""} · {releaseTargetProbe.latencyMs} ms</small>}{releaseTargetProbeError && <small className="lifecycle-health-reason">{releaseTargetProbeError}</small>}{platformReadiness.blockingReasonCodes.length > 0 && <small className="lifecycle-health-reason">阻塞项：{platformReadiness.blockingReasonCodes.join("、")}</small>}{releaseTargetProbes.length > 0 && <div className="release-target-probe-history" data-testid="release-target-probe-history"><div><strong>探测历史 {releaseTargetProbes.length} 次</strong><span>失败 {releaseTargetFailureCount}</span><span>超时 {releaseTargetTimeoutCount}</span></div>{releaseTargetProbes.slice(0, 10).map((item, index) => <div className="release-target-probe-history-row" key={`${item.checkedAt}-${item.status}-${index}`}><strong>{item.status}</strong><span>{item.reasonCode}</span><small>{item.httpStatus ? `HTTP ${item.httpStatus} · ` : ""}{item.latencyMs} ms{item.checkedAt ? ` · ${item.checkedAt}` : ""}</small></div>)}</div>}</section>
    <section className="panel production-evidence" data-testid="production-evidence"><div className="section-heading"><div><h2>生产交付证据台账</h2><p>只保存状态、责任人、脱敏引用和有效期；不保存报告正文、凭据或外部响应。</p></div><strong>{productionEvidence.filter((item) => item.status === "ACCEPTED").length}/{productionEvidence.length || 0} 已核验</strong></div>{productionEvidence.length ? <div className="production-evidence-list">{productionEvidence.map((item) => { const draft = productionEvidenceDrafts[item.evidenceId] || item; return <div className="production-evidence-row" key={item.evidenceId}><div className="production-evidence-main"><strong>{item.evidenceId}</strong><small>revision {item.revision}{item.updatedAt ? ` · ${item.updatedAt}` : ""}</small></div><select aria-label={`生产证据状态 ${item.evidenceId}`} value={draft.status || item.status} onChange={(event) => updateEvidenceDraft(item.evidenceId, "status", event.target.value)}><option value="MISSING">MISSING</option><option value="SUBMITTED">SUBMITTED</option><option value="ACCEPTED">ACCEPTED</option><option value="REJECTED">REJECTED</option></select><input aria-label={`生产证据引用 ${item.evidenceId}`} value={draft.evidenceRef || ""} onChange={(event) => updateEvidenceDraft(item.evidenceId, "evidenceRef", event.target.value)} placeholder="脱敏引用" /><button type="button" data-testid={`save-production-evidence-${item.evidenceId}`} onClick={() => saveProductionEvidence(item)}>保存</button><small className="production-evidence-summary">{item.summary || "尚无核验摘要"}{item.expiresAt ? ` · 到期 ${item.expiresAt}` : ""}</small></div>; })}</div> : <div className="empty-state">生产证据台账接口暂不可用。</div>}{productionEvidenceError && <small className="lifecycle-health-reason">{productionEvidenceError}</small>}</section>
    <section className={`panel lifecycle-health lifecycle-health-${String(lifecycle.state).toLowerCase()}`} data-testid="lifecycle-health"><div className="section-heading"><div><h2>Skill 生命周期健康</h2><p>只读展示事实源与关系投影的一致性，不执行导入或修复。</p></div><strong>{lifecycleStateLabel(lifecycle.state)}{lifecycle.state !== "UNKNOWN" ? ` (${lifecycle.state})` : ""}</strong></div><div className="lifecycle-health-grid"><div><span>Backend</span><strong>{lifecycle.backend}</strong></div><div><span>Revision</span><strong>{lifecycle.revision}</strong></div><div><span>投影年龄</span><strong>{lifecycleAgeLabel(lifecycle.projectionAgeSeconds)}</strong><small>{lifecycle.maxProjectionAgeSeconds ? `阈值 ${lifecycle.maxProjectionAgeSeconds} 秒` : ""}</small></div><div><span>计数差异</span><strong>{lifecycleDeltaLabel(lifecycle.countDelta)}</strong></div></div>{lifecycleError ? <small className="lifecycle-health-reason">{lifecycleError}</small> : lifecycle.reasonCode && <small className="lifecycle-health-reason">{lifecycle.reasonCode}</small>}</section>
    <section className={`panel optimization-work-item-health optimization-work-item-health-${String(optimizationWorkItemHealth.status).toLowerCase()}`} data-testid="optimization-work-item-health"><div className="section-heading"><div><h2>优化闭环健康</h2><p>只读识别长期未推进的优化工作项，不自动修改状态。</p></div><strong>{optimizationWorkItemHealth.staleCount} 个滞留</strong></div><div className="optimization-work-item-health-grid"><div><span>活跃工作项</span><strong>{optimizationWorkItemHealth.activeCount}</strong></div><div><span>滞留阈值</span><strong>{Math.round(optimizationWorkItemHealth.staleThresholdSeconds / 86400)} 天</strong></div><div><span>按状态</span><strong>{workItemBreakdown(optimizationWorkItemHealth.staleByStatus)}</strong></div><div><span>按 Owner</span><strong>{workItemBreakdown(optimizationWorkItemHealth.staleByOwner)}</strong></div><div><span>按严重度</span><strong>{workItemBreakdown(optimizationWorkItemHealth.staleBySeverity)}</strong></div></div><small className="lifecycle-health-reason">{optimizationWorkItemHealth.reasonCode}</small>{optimizationWorkItemHealth.staleCount > 0 && <button type="button" className="secondary-button" data-testid="optimization-work-item-health-link" onClick={() => onNavigate ? onNavigate("quality") : (typeof window !== "undefined" ? (window.location.hash = "#/quality") : undefined)}>查看质量管理</button>}</section>
    <section className="panel runtime-filters"><label><span>Skill ID</span><input value={runtimeSkillId} onChange={(event) => setRuntimeSkillId(event.target.value)} placeholder="全部 Skill" /></label><label><span>版本</span><input value={runtimeVersion} onChange={(event) => setRuntimeVersion(event.target.value)} placeholder="全部版本" /></label><label><span>团队</span><input value={runtimeTeamId} onChange={(event) => setRuntimeTeamId(event.target.value)} placeholder="全部团队" /></label><label><span>数据来源</span><select value={runtimeSource} onChange={(event) => setRuntimeSource(event.target.value)}><option value="all">全部来源</option><option value="production">生产调用</option><option value="mock">Mock 受控运行</option></select></label><label><span>Trace ID</span><input aria-label="Trace ID" value={traceId} onChange={(event) => setTraceId(event.target.value)} placeholder="全部 Trace" /></label><label><span>Trace 状态</span><select aria-label="Trace 状态" value={traceStatus} onChange={(event) => setTraceStatus(event.target.value)}><option value="">全部状态</option><option value="failure">失败</option><option value="timeout">超时</option><option value="success">成功</option><option value="cancelled">已取消</option></select></label><label><span>Runtime ID</span><input aria-label="Runtime ID" value={runtimeRuntimeId} onChange={(event) => setRuntimeRuntimeId(event.target.value)} placeholder="全部 Runtime" /></label><label><span>MCP Server ID</span><input aria-label="MCP Server ID" value={runtimeMcpServerId} onChange={(event) => setRuntimeMcpServerId(event.target.value)} placeholder="全部 MCP Server" /></label><label><span>LLM Provider ID</span><input aria-label="LLM Provider ID" value={runtimeLlmProviderId} onChange={(event) => setRuntimeLlmProviderId(event.target.value)} placeholder="全部 LLM Provider" /></label></section>
    {runtimeError && <div className="api-state error-state">{runtimeError}</div>}
    {runtimeLoading ? <div className="panel runtime-empty">正在加载 Skill 运行指标…</div> : <>
      <section className="runtime-summary-grid"><div className="panel"><span>运行总量</span><strong>{runtime.totals.total}</strong><small>{sourceLabel(runtime.dataSource)}</small></div><div className="panel"><span>成功率</span><strong>{runtime.totals.successRate.toFixed(1)}%</strong><small>{runtime.totals.successes} 次成功</small></div><div className="panel"><span>失败 / 超时</span><strong>{runtime.totals.failures} / {runtime.totals.timeouts}</strong><small>取消 {runtime.totals.cancellations} 次</small></div><div className="panel"><span>P95 延迟</span><strong>{runtime.latency.p95Ms} ms</strong><small>样本 {runtime.latency.sampleCount}</small></div></section>
      <section className="operations-grid runtime-grid"><article className="panel"><div className="section-heading"><div><h2>数据来源</h2><p>Mock 与生产调用分层展示</p></div></div>{runtime.sources.length ? <div className="runtime-source-list">{runtime.sources.map((item) => <div key={item.dataSource}><div><strong>{sourceLabel(item.dataSource)}</strong><small>{item.total} 次 · P95 {item.p95Ms} ms</small></div><span>{item.successRate.toFixed(1)}%</span></div>)}</div> : <div className="empty-state">当前窗口暂无运行数据</div>}</article><article className="panel"><div className="section-heading"><div><h2>错误码分布</h2><p>仅显示失败和超时的标准错误码</p></div></div>{runtime.errors.length ? <div className="runtime-error-list">{runtime.errors.map((item) => <div key={item.errorCode}><strong>{item.errorCode}</strong><span>{item.count} 次 · {item.percentage.toFixed(1)}%</span></div>)}</div> : <div className="empty-state">当前窗口暂无失败或超时</div>}</article></section>
      <section className="operations-grid runtime-grid"><article className="panel"><div className="section-heading"><div><h2>版本采用</h2><p>按运行次数计算版本占比</p></div></div>{runtime.versionAdoption.length ? <div className="runtime-version-list">{runtime.versionAdoption.map((item) => <div key={item.version}><strong>{item.version}</strong><span>{item.calls} 次 · {item.percentage.toFixed(1)}%</span></div>)}</div> : <div className="empty-state">暂无版本运行数据</div>}</article><article className="panel"><div className="section-heading"><div><h2>运行趋势</h2><p>按当前时间窗口自动聚合</p></div></div>{runtime.trend.some((item) => item.calls > 0) ? <div className="runtime-trend-list">{runtime.trend.filter((item) => item.calls > 0).slice(-8).map((item) => <div key={item.bucket}><span>{item.bucket}</span><strong>{item.calls} 次</strong><small>{item.successRate.toFixed(1)}% 成功 · P95 {item.p95Ms} ms</small></div>)}</div> : <div className="empty-state">当前窗口暂无趋势数据</div>}</article></section>
    </>}
    {traceError && <div className="api-state error-state">{traceError}</div>}
    <section className="panel trace-failure-panel"><div className="section-heading"><div><h2>{tracePanelTitle}</h2><p>{tracePanelDescription}</p></div><span className="trace-count">{visibleTraces.length} 条异常</span></div>{traceLoading ? <div className="runtime-empty">正在加载 Trace…</div> : visibleTraces.length ? <div className="trace-list">{visibleTraces.slice(0, 20).map((item) => <article className="trace-row" key={`${item.traceId}-${item.spanId}`}><div><strong>{item.errorCode || "UNKNOWN_ERROR"}</strong><small>{item.operation} · {item.traceId} · {item.spanId}</small></div><span>{traceStatusLabel(item.status)}</span><span>{item.skillId} v{item.version}</span><time>{item.durationMs} ms · {item.occurredAt || "—"}{item.runtimeId || item.mcpServerId || item.llmProviderId ? ` · ${item.runtimeId || "Runtime—"} / ${item.mcpServerId || "MCP—"} / ${item.llmProviderId || "LLM—"}` : ""}</time></article>)}</div> : <div className="empty-state">当前窗口暂无{traceEmptyLabel}</div>}</section>
    <div className="section-heading operations-platform-heading"><div><h2>平台服务监控</h2><p>保留原有 API 请求、服务健康和安全事件指标。</p></div><select value={window} onChange={(event) => setWindow(event.target.value)} aria-label="平台指标时间窗口">{OPERATIONS_WINDOWS.map((item) => <option value={item.value} key={item.value}>{item.label}</option>)}</select></div>
    {error && <div className="api-state error-state">{error}</div>}
    <section className="operations-health panel"><div><span>服务状态</span><strong className={metrics.health.status === "UP" ? "health-up" : "health-warning"}>{healthLabel(metrics.health.status)}</strong></div><div><span>制品存储</span><strong>{metrics.health.packageStorage}</strong></div><div><span>调用持久化</span><strong>{metrics.health.invocationPersistence}</strong></div><div><span>指标持久化</span><strong>{metrics.health.metricsPersistence}</strong></div><small>{metrics.generatedAt ? `更新时间 ${metrics.generatedAt}` : "当前窗口暂无采集时间"}</small></section>
    <section className="operations-alerts panel"><div className="section-heading"><div><h2>阈值告警</h2><p>根据当前窗口聚合值评估，状态迁移时复用现有通知链路。</p></div><strong className="operations-alert-count">{alerts.alerts.filter((item) => item.status === "ACTIVE").length} 条 active</strong></div>{alerts.alerts.length ? <div className="operations-alert-list">{alerts.alerts.map((alert) => <div className={`operations-alert-row ${alert.status === "ACTIVE" ? "is-active" : "is-resolved"}`} key={`${alert.rule}-${alert.eventCode || "all"}`}><div><strong>{alertLabel(alert)}</strong><small>{alertValue(alert)}{alert.eventCode ? ` · ${alert.eventCode}` : ""}</small></div><span>{alert.status === "ACTIVE" ? "ACTIVE" : "RESOLVED"}</span></div>)}</div> : <div className="empty-state">当前窗口暂无告警规则结果</div>}</section>
    <section className="operations-kpis"><div className="panel"><span>请求总量</span><strong>{metrics.requests.total}</strong><small>API 聚合请求</small></div><div className="panel"><span>成功请求</span><strong>{metrics.requests.successes}</strong><small>2xx / 3xx</small></div><div className="panel"><span>客户端错误</span><strong>{metrics.requests.clientErrors}</strong><small>4xx</small></div><div className="panel"><span>服务端错误</span><strong>{metrics.requests.serverErrors}</strong><small>5xx</small></div></section>
    <section className="operations-grid"><article className="panel"><div className="section-heading"><div><h2>请求延迟</h2><p>固定桶近似分位数</p></div></div><div className="operations-latency"><div><span>P50</span><strong>{metrics.latency.p50Ms} ms</strong></div><div><span>P95</span><strong>{metrics.latency.p95Ms} ms</strong></div><div><span>最大</span><strong>{metrics.latency.maxMs} ms</strong></div></div></article><article className="panel"><div className="section-heading"><div><h2>安全事件</h2><p>限流、来源和幂等保护计数</p></div></div>{metrics.securityEvents.length ? <div className="operations-events">{metrics.securityEvents.map((item) => <div key={item.code}><strong>{item.code}</strong><span>{item.count}</span></div>)}</div> : <div className="empty-state">当前窗口暂无安全事件</div>}</article></section>
  </main>;
}
