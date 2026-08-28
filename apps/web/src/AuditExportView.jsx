import { useEffect, useMemo, useState } from "react";
import {
  canViewExportWorkbench,
  auditMetadataEntries,
  formatExportError,
  formatExportStatus,
  isExportTerminal,
  normalizeAuditEvents,
  normalizeRetentionPreview,
  validateRetentionForm,
} from "./exportGovernance.js";

const DATASETS = [
  ["AUDIT_SUMMARY", "审计摘要"],
  ["INVOCATION_SUMMARY", "调用统计摘要"],
  ["INSTALLATION_SUMMARY", "安装统计摘要"],
];

function errorMessage(error, fallback) {
  return error?.code ? formatExportError(error) : (error?.message || fallback);
}

export function AuditExportView({ api, role, onToast }) {
  const canView = canViewExportWorkbench(role);
  const [jobs, setJobs] = useState([]);
  const [retention, setRetention] = useState(null);
  const [preview, setPreview] = useState(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [auditEvents, setAuditEvents] = useState([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [auditError, setAuditError] = useState("");
  const [auditDraft, setAuditDraft] = useState({ action: "", resourceType: "" });
  const [auditQuery, setAuditQuery] = useState({ action: "", resourceType: "" });
  const [auditRefresh, setAuditRefresh] = useState(0);
  const [form, setForm] = useState({ dataset: "AUDIT_SUMMARY", format: "CSV", skillId: "", teamId: "", clientType: "", status: "" });
  const [retentionForm, setRetentionForm] = useState({ auditRetentionDays: 365, invocationRetentionDays: 90, installationRetentionDays: 90 });
  const [retentionError, setRetentionError] = useState("");

  const load = async () => {
    if (!canView) return;
    setLoading(true);
    try {
      const [jobsResponse, retentionResponse] = await Promise.all([api.listExports(), api.getRetentionPolicy()]);
      setJobs(jobsResponse.data ?? []);
      if (retentionResponse.data) {
        setRetention(retentionResponse.data);
        setRetentionForm({
          auditRetentionDays: retentionResponse.data.auditRetentionDays,
          invocationRetentionDays: retentionResponse.data.invocationRetentionDays,
          installationRetentionDays: retentionResponse.data.installationRetentionDays,
        });
      }
      setError("");
    } catch (loadError) {
      setError(errorMessage(loadError, "导出治理数据加载失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [role]);

  useEffect(() => {
    if (!canView || role !== "admin" || !api.listAudit) {
      setAuditEvents([]);
      return undefined;
    }
    let active = true;
    setAuditLoading(true);
    api.listAudit(auditQuery)
      .then((response) => {
        if (!active) return;
        setAuditEvents(normalizeAuditEvents(response.data));
        setAuditError("");
      })
      .catch((loadError) => {
        if (active) setAuditError(errorMessage(loadError, "审计事件加载失败"));
      })
      .finally(() => { if (active) setAuditLoading(false); });
    return () => { active = false; };
  }, [api, canView, role, auditQuery.action, auditQuery.resourceType, auditRefresh]);

  useEffect(() => {
    if (!canView || !jobs.some((job) => !isExportTerminal(job.status))) return undefined;
    const timer = window.setInterval(() => {
      api.listExports().then((response) => setJobs(response.data ?? [])).catch(() => {});
    }, 2500);
    return () => window.clearInterval(timer);
  }, [api, canView, jobs]);

  const sortedJobs = useMemo(() => [...jobs].sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))), [jobs]);

  if (!canView) {
    return <main className="content api-state"><strong>当前角色无权访问导出与保留策略工作台</strong></main>;
  }

  const createExport = async (event) => {
    event.preventDefault();
    setSubmitting(true);
    try {
      const filters = Object.fromEntries(Object.entries({ skillId: form.skillId, teamId: form.teamId, clientType: form.clientType, status: form.status }).filter(([, value]) => value));
      const response = await api.createExport({ dataset: form.dataset, format: form.format, filters });
      setJobs((current) => [response.data, ...current]);
      onToast?.("导出任务已创建");
    } catch (createError) {
      setError(errorMessage(createError, "导出任务创建失败"));
    } finally {
      setSubmitting(false);
    }
  };

  const download = async (job) => {
    try {
      const response = await api.issueExportDownloadUrl(job.jobId);
      const url = response.data?.url;
      if (url && typeof window !== "undefined") window.open(url, "_blank", "noopener,noreferrer");
    } catch (downloadError) {
      setError(errorMessage(downloadError, "下载链接生成失败"));
    }
  };

  const retry = async (job) => {
    try {
      const response = await api.retryExport(job.jobId);
      setJobs((current) => current.map((item) => item.jobId === job.jobId ? response.data : item));
    } catch (retryError) {
      setError(errorMessage(retryError, "导出重试失败"));
    }
  };

  const saveRetention = async () => {
    const errors = validateRetentionForm(retentionForm);
    if (Object.keys(errors).length) {
      setRetentionError(Object.values(errors)[0]);
      return;
    }
    try {
      const response = await api.updateRetentionPolicy({ ...retentionForm, policyVersion: retention.policyVersion });
      setRetention(response.data);
      onToast?.("保留策略已更新");
      setRetentionError("");
    } catch (saveError) {
      setRetentionError(errorMessage(saveError, "保留策略更新失败"));
    }
  };

  const previewRetention = async () => {
    try {
      const response = await api.previewRetention();
      setPreview(response.data);
      setRetentionError("");
    } catch (previewError) {
      setRetentionError(errorMessage(previewError, "保留策略预览失败"));
    }
  };

  const executeRetention = async () => {
    if (!preview || !window.confirm("确认清理保留窗口外的数据吗？被生命周期资产引用的质量证据不会被删除。")) return;
    try {
      await api.executeRetention({ previewId: preview.previewId, policyVersion: preview.policyVersion, executionId: `web-${Date.now()}` });
      setPreview(null);
      await load();
      onToast?.("保留策略清理已执行");
    } catch (executeError) {
      setRetentionError(errorMessage(executeError, "保留策略执行失败"));
    }
  };

  return <main className="content export-content">
    <div className="page-heading"><div><h1>审计与导出</h1><p>以最小字段集导出审计、调用和安装摘要，并控制数据保留窗口。</p></div><span className="badge badge-category">{role === "admin" ? "管理员" : "审计操作员"}</span></div>
    {error && <div className="api-state error-state">{error}</div>}
    <section className="panel export-panel">
      <div className="panel-heading"><div><h2>创建受控导出</h2><p>产物异步生成，下载链接默认 15 分钟有效且只能使用一次。</p></div></div>
      <form className="export-form" onSubmit={createExport}>
        <label>数据集<select value={form.dataset} onChange={(event) => setForm({ ...form, dataset: event.target.value })}>{DATASETS.map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></label>
        <label>格式<select value={form.format} onChange={(event) => setForm({ ...form, format: event.target.value })}><option value="CSV">CSV</option><option value="JSON">JSON</option></select></label>
        <label>Skill ID<input value={form.skillId} onChange={(event) => setForm({ ...form, skillId: event.target.value })} placeholder="可选" /></label>
        <label>团队 ID<input value={form.teamId} onChange={(event) => setForm({ ...form, teamId: event.target.value })} placeholder="可选" /></label>
        <label>客户端<input value={form.clientType} onChange={(event) => setForm({ ...form, clientType: event.target.value })} placeholder="可选" /></label>
        <label>状态<input value={form.status} onChange={(event) => setForm({ ...form, status: event.target.value })} placeholder="可选" /></label>
        <button className="primary-action export-submit" disabled={submitting}>{submitting ? "创建中…" : "创建导出任务"}</button>
      </form>
    </section>
    <section className="panel export-panel">
      <div className="panel-heading"><div><h2>导出任务</h2><p>只展示当前角色可见的任务及安全失败原因。</p></div><button className="secondary-button" onClick={load} disabled={loading}>刷新</button></div>
      {loading ? <div className="api-state">正在加载导出任务…</div> : sortedJobs.length === 0 ? <div className="empty-state"><strong>暂无导出任务</strong><span>创建一个摘要导出开始。</span></div> : <div className="export-table-wrap"><table className="export-table"><thead><tr><th>数据集</th><th>格式</th><th>状态</th><th>记录数</th><th>创建时间</th><th>操作</th></tr></thead><tbody>{sortedJobs.map((job) => <tr key={job.jobId}><td>{DATASETS.find(([value]) => value === job.dataset)?.[1] || job.dataset}</td><td>{job.format}</td><td><span className={`badge badge-${job.status === "FAILED" ? "danger" : job.status === "COMPLETED" ? "success" : "neutral"}`}>{formatExportStatus(job.status)}</span>{job.failureMessage && <small className="table-error">{job.failureMessage}</small>}</td><td>{job.rowCount ?? 0}</td><td>{job.createdAt ? new Date(job.createdAt).toLocaleString("zh-CN") : "-"}</td><td className="table-actions">{job.status === "COMPLETED" && <button className="text-button" onClick={() => download(job)}>下载</button>}{job.status === "FAILED" && role === "admin" && <button className="text-button" onClick={() => retry(job)}>重试</button>}</td></tr>)}</tbody></table></div>}
    </section>
    {role === "admin" && <section className="panel export-panel" data-testid="audit-event-workbench">
      <div className="panel-heading"><div><h2>生命周期审计事件</h2><p>查看发布阻断、质量证据和优化闭环事件；页面只展示白名单元数据，不展示业务正文或凭据。</p></div><button className="secondary-button" type="button" onClick={() => setAuditRefresh((value) => value + 1)} disabled={auditLoading}>刷新</button></div>
      <form className="export-form" onSubmit={(event) => { event.preventDefault(); setAuditQuery({ action: auditDraft.action.trim(), resourceType: auditDraft.resourceType.trim() }); }}>
        <label>动作<input value={auditDraft.action} onChange={(event) => setAuditDraft({ ...auditDraft, action: event.target.value })} placeholder="如 PACKAGE_APPROVAL_BLOCKED" /></label>
        <label>资源类型<input value={auditDraft.resourceType} onChange={(event) => setAuditDraft({ ...auditDraft, resourceType: event.target.value })} placeholder="如 SKILL_VERSION" /></label>
        <button className="secondary-button export-submit" type="submit">应用筛选</button>
      </form>
      {auditError && <div className="api-state error-state">{auditError}</div>}
      {auditLoading ? <div className="api-state">正在加载审计事件…</div> : auditEvents.length === 0 ? <div className="empty-state"><strong>暂无匹配的生命周期事件</strong><span>可以清空筛选后重新查询。</span></div> : <div className="export-table-wrap"><table className="export-table"><thead><tr><th>动作</th><th>资源</th><th>操作者</th><th>请求</th><th>时间</th><th>安全上下文</th></tr></thead><tbody>{auditEvents.map((event) => <tr key={event.auditId || `${event.action}-${event.occurredAt}`}><td><span className="badge badge-category">{event.action}</span></td><td>{event.resourceType}<small className="table-error">{event.resourceId || "-"}</small></td><td>{event.actorId || "-"}<small className="table-error">{event.actorRole || "-"}</small></td><td>{event.requestId || "-"}</td><td>{event.occurredAt ? new Date(event.occurredAt).toLocaleString("zh-CN") : "-"}</td><td>{auditMetadataEntries(event).map(([label, value]) => <small key={`${label}-${value}`} className="table-error">{label}：{value}</small>)}</td></tr>)}</tbody></table></div>}
    </section>}
    <section className="panel export-panel retention-panel">
      <div className="panel-heading"><div><h2>数据保留策略</h2><p>被优化工作项、实验或发布门禁引用的质量证据会被保护，引用变化时清理会安全拒绝。</p></div>{retention && <span className="policy-version">版本 {retention.policyVersion}</span>}</div>
      <div className="retention-grid"><label>审计保留天数<input type="number" min="365" value={retentionForm.auditRetentionDays} onChange={(event) => setRetentionForm({ ...retentionForm, auditRetentionDays: Number(event.target.value) })} disabled={role !== "admin"} /></label><label>调用保留天数<input type="number" min="30" value={retentionForm.invocationRetentionDays} onChange={(event) => setRetentionForm({ ...retentionForm, invocationRetentionDays: Number(event.target.value) })} disabled={role !== "admin"} /></label><label>安装保留天数<input type="number" min="30" value={retentionForm.installationRetentionDays} onChange={(event) => setRetentionForm({ ...retentionForm, installationRetentionDays: Number(event.target.value) })} disabled={role !== "admin"} /></label></div>
      {retentionError && <div className="form-error">{retentionError}</div>}
      {role === "admin" && <div className="retention-actions"><button className="secondary-button" onClick={saveRetention}>保存策略</button><button className="secondary-button" onClick={previewRetention}>预览清理影响</button></div>}
      {preview && (() => { const counts = normalizeRetentionPreview(preview); return <div className="retention-preview"><strong>预览结果</strong><span>调用可清理 {counts.invocationEligibleCount} 条</span><span>安装可清理 {counts.installationEligibleCount} 条</span><span>运行摘要可清理 {counts.runtimeSummaryEligibleCount} 条</span><span>Runner 执行可清理 {counts.runnerExecutionEligibleCount} 条</span><span>质量证据可清理 {counts.qualityEvidenceEligibleCount} 条</span><span>Benchmark 可清理 {counts.benchmarkEligibleCount} 条</span><span>兼容矩阵可清理 {counts.compatibilityMatrixEligibleCount} 条</span><span>审计可归档 {counts.auditArchiveEligibleCount} 条</span><span>保护评测运行 {counts.protectedEvaluationRunCount} 条</span><span>保护质量快照 {counts.protectedQualitySnapshotCount} 条</span><span>保护 Benchmark {counts.protectedBenchmarkCount} 条</span><span>保护兼容矩阵 {counts.protectedCompatibilityMatrixCount} 条</span><span>保护引用 {counts.protectedReferenceCount} 条</span>{counts.protectionFingerprint && <small title={counts.protectionFingerprint}>保护指纹 {counts.protectionFingerprint.slice(0, 12)}…</small>}<button className="danger-button" onClick={executeRetention}>确认执行清理</button></div>; })()}
    </section>
  </main>;
}
