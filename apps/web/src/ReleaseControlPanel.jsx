import { useEffect, useState } from "react";

const releaseStatusLabels = {
  REQUESTED: "待审批",
  APPROVED: "已批准",
  PROMOTING: "晋级中",
  PROMOTED: "已晋级",
  REJECTED: "已拒绝",
  ROLLBACK_REVIEW: "待回滚复核",
  ROLLING_BACK: "回滚中",
  ROLLED_BACK: "已回滚",
  FAILED: "执行失败",
};

export function ReleaseControlPanel({ api, role, skillId, version }) {
  const [releases, setReleases] = useState([]);
  const [admission, setAdmission] = useState(null);
  const [targetEnvironment, setTargetEnvironment] = useState("STAGING");
  const [rollbackTargetVersion, setRollbackTargetVersion] = useState("");
  const [loadingId, setLoadingId] = useState("");
  const [error, setError] = useState("");

  const load = async () => {
    const [releaseResponse, admissionResponse] = await Promise.all([
      api.listReleases({ skillId, version }),
      api.getReleaseAdmission ? api.getReleaseAdmission(skillId, version) : Promise.resolve({ data: null }),
    ]);
    setReleases(releaseResponse.data ?? []);
    setAdmission(admissionResponse.data ?? null);
  };
  useEffect(() => {
    if (!api.listReleases || role !== "admin") return undefined;
    setError("");
    load().catch((loadError) => setError(loadError.message || "发布记录加载失败"));
    return undefined;
  }, [api, role, skillId, version]);

  const run = async (releaseId, action) => {
    setLoadingId(releaseId);
    setError("");
    try {
      await action(releaseId);
      await load();
    } catch (actionError) {
      setError(actionError.message || "发布操作失败");
    } finally {
      setLoadingId("");
    }
  };

  const create = async (event) => {
    event.preventDefault();
    setLoadingId("new");
    setError("");
    try {
      await api.createRelease({ skillId, version, targetEnvironment, sourceAssessmentId: "", idempotencyKey: `quality-center-${skillId}-${version}-${targetEnvironment}` });
      await load();
    } catch (createError) {
      setError(createError.message || "发布请求创建失败");
    } finally {
      setLoadingId("");
    }
  };

  const requestRollback = (release) => run(release.releaseId, (releaseId) => api.requestReleaseRollbackReview(releaseId, {
    reason: "Quality Center 发起人工回滚复核",
    assessmentId: "",
    targetVersion: rollbackTargetVersion.trim(),
    targetReleaseId: "",
  }));

  if (!api.listReleases || role !== "admin") return null;
  return <section className="panel release-control-panel" data-testid="release-control-panel">
    <div className="section-heading"><div><h2>受控发布批次</h2><p>发布晋级、回滚和质量门禁证据均需显式人工操作；页面加载只读。</p></div><span className="quality-source-badge">Release Control Plane</span></div>
    {admission && <div className={`release-admission-summary ${admission.allowed ? "is-allowed" : "is-blocked"}`} data-testid="release-admission-summary"><strong>分发准入：{admission.allowed ? "允许" : "阻断"}</strong><span>{admission.mode === "CONTROLLED" ? "CONTROLLED · 必须匹配生产晋级" : "LEGACY_COMPATIBLE · 无生产批次时兼容历史版本"}</span><small>{admission.reasonCode}{admission.releaseId ? ` · ${admission.releaseId}` : ""}</small></div>}
    <form className="release-request-form" onSubmit={create}>
      <label><span>目标环境</span><select value={targetEnvironment} onChange={(event) => setTargetEnvironment(event.target.value)}><option value="STAGING">STAGING</option><option value="PRODUCTION">PRODUCTION</option></select></label>
      <button className="primary-action" type="submit" disabled={loadingId === "new"}>{loadingId === "new" ? "创建中…" : "发起发布请求"}</button>
    </form>
    {error && <div className="api-state error-state" role="alert">{error}</div>}
    {releases.length ? <div className="release-list">{releases.map((release) => <article className="release-row" key={release.releaseId}>
      <div><strong>{release.version} · {release.targetEnvironment}</strong><small>{release.releaseId} · SHA {String(release.sha256 || "").slice(0, 12)} · {release.gateSnapshot?.outcome || "UNKNOWN"}</small>{release.gateSnapshot?.reasonCodes?.length ? <small>门禁原因：{release.gateSnapshot.reasonCodes.join("、")}</small> : null}</div>
      <div className="release-row-actions"><span className={`quality-status quality-status-${String(release.status || "").toLowerCase()}`}>{releaseStatusLabels[release.status] || release.status}</span>{release.status === "REQUESTED" && <button type="button" className="text-button" disabled={loadingId === release.releaseId} onClick={() => run(release.releaseId, api.approveRelease)}>审批</button>}{release.status === "APPROVED" && <button type="button" className="text-button" disabled={loadingId === release.releaseId} onClick={() => run(release.releaseId, api.promoteRelease)}>晋级</button>}{release.status === "PROMOTED" && <><input aria-label="回滚目标版本" placeholder="回滚目标版本" value={rollbackTargetVersion} onChange={(event) => setRollbackTargetVersion(event.target.value)} /><button type="button" className="text-button" disabled={loadingId === release.releaseId || !rollbackTargetVersion.trim()} onClick={() => requestRollback(release)}>发起回滚复核</button></>}{release.status === "ROLLBACK_REVIEW" && <button type="button" className="text-button" disabled={loadingId === release.releaseId} onClick={() => run(release.releaseId, api.rollbackRelease)}>执行回滚</button>}</div>
    </article>)}</div> : <div className="empty-state">当前版本暂无发布批次。</div>}
  </section>;
}
