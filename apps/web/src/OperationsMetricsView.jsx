import { useEffect, useState } from "react";
import { OPERATIONS_WINDOWS, normalizeOperationsAlerts, normalizeOperationsMetrics } from "./operationsMetrics.js";
import "./operationsMetrics.css";

function healthLabel(value) {
  return value === "UP" ? "正常" : value === "UNKNOWN" ? "未知" : "需关注";
}

function alertLabel(alert) {
  if (alert.eventCode) return `${alert.eventCode} 安全事件`;
  if (alert.rule === "P95_LATENCY") return "P95 延迟";
  if (alert.rule === "SERVER_ERROR_RATE") return "5xx 错误率";
  return alert.rule;
}

function alertValue(alert) {
  if (alert.unit === "ratio") return `${(alert.currentValue * 100).toFixed(1)}% / ${(alert.threshold * 100).toFixed(1)}%`;
  return `${alert.currentValue}${alert.unit ? ` ${alert.unit}` : ""} / ${alert.threshold}${alert.unit ? ` ${alert.unit}` : ""}`;
}

export function OperationsMetricsView({ api, role }) {
  const [window, setWindow] = useState("15m");
  const [metrics, setMetrics] = useState(() => normalizeOperationsMetrics(null));
  const [alerts, setAlerts] = useState(() => normalizeOperationsAlerts(null));
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    if (role !== "admin") return;
    let active = true;
    setLoading(true);
    setError("");
    Promise.all([api.getOperationsMetrics(window), api.getOperationsAlerts(window)])
      .then(([metricsResponse, alertsResponse]) => {
        if (active) {
          setMetrics(normalizeOperationsMetrics(metricsResponse));
          setAlerts(normalizeOperationsAlerts(alertsResponse));
        }
      })
      .catch((loadError) => { if (active) setError(loadError.message || "运行指标加载失败"); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [api, role, window]);

  if (role !== "admin") return <main className="content api-state error-state">当前角色无权查看运行监控</main>;
  if (loading) return <main className="content api-state">正在加载运行指标…</main>;

  return <main className="content operations-content">
    <div className="page-title-row"><div><h1>运行监控</h1><p>只展示平台级聚合指标，不包含请求正文、Token 或用户明细。</p></div><select value={window} onChange={(event) => setWindow(event.target.value)} aria-label="指标时间窗口">{OPERATIONS_WINDOWS.map((item) => <option value={item.value} key={item.value}>{item.label}</option>)}</select></div>
    {error && <div className="api-state error-state">{error}</div>}
    <section className="operations-health panel"><div><span>服务状态</span><strong className={metrics.health.status === "UP" ? "health-up" : "health-warning"}>{healthLabel(metrics.health.status)}</strong></div><div><span>制品存储</span><strong>{metrics.health.packageStorage}</strong></div><div><span>调用持久化</span><strong>{metrics.health.invocationPersistence}</strong></div><div><span>指标持久化</span><strong>{metrics.health.metricsPersistence}</strong></div><small>{metrics.generatedAt ? `更新时间 ${metrics.generatedAt}` : "当前窗口暂无采集时间"}</small></section>
    <section className="operations-alerts panel"><div className="section-heading"><div><h2>阈值告警</h2><p>根据当前窗口聚合值评估，未发送外部通知。</p></div><strong className="operations-alert-count">{alerts.alerts.filter((item) => item.status === "ACTIVE").length} 条 active</strong></div>{alerts.alerts.length ? <div className="operations-alert-list">{alerts.alerts.map((alert) => <div className={`operations-alert-row ${alert.status === "ACTIVE" ? "is-active" : "is-resolved"}`} key={`${alert.rule}-${alert.eventCode || "all"}`}><div><strong>{alertLabel(alert)}</strong><small>{alertValue(alert)}</small></div><span>{alert.status === "ACTIVE" ? "ACTIVE" : "RESOLVED"}</span></div>)}</div> : <div className="empty-state">当前窗口暂无告警规则结果</div>}</section>
    <section className="operations-kpis"><div className="panel"><span>请求总量</span><strong>{metrics.requests.total}</strong><small>API 聚合请求</small></div><div className="panel"><span>成功请求</span><strong>{metrics.requests.successes}</strong><small>2xx / 3xx</small></div><div className="panel"><span>客户端错误</span><strong>{metrics.requests.clientErrors}</strong><small>4xx</small></div><div className="panel"><span>服务端错误</span><strong>{metrics.requests.serverErrors}</strong><small>5xx</small></div></section>
    <section className="operations-grid"><article className="panel"><div className="section-heading"><div><h2>请求延迟</h2><p>固定桶近似分位数</p></div></div><div className="operations-latency"><div><span>P50</span><strong>{metrics.latency.p50Ms} ms</strong></div><div><span>P95</span><strong>{metrics.latency.p95Ms} ms</strong></div><div><span>最大</span><strong>{metrics.latency.maxMs} ms</strong></div></div></article><article className="panel"><div className="section-heading"><div><h2>安全事件</h2><p>限流、来源和幂等保护计数</p></div></div>{metrics.securityEvents.length ? <div className="operations-events">{metrics.securityEvents.map((item) => <div key={item.code}><strong>{item.code}</strong><span>{item.count}</span></div>)}</div> : <div className="empty-state">当前窗口暂无安全事件</div>}</article></section>
  </main>;
}
