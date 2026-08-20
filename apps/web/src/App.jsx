import { useEffect, useMemo, useRef, useState } from "react";
import { NAV_ITEMS, ROLES, MARKET_SORT_OPTIONS, formatMarketTitle, formatMetric, getNavigationForRole } from "./state.js";
import { authenticateAdmin, createDeveloperSession, clearSession, readSession, saveSession, LOGIN_MODES, requiresAdminCredentials } from "./auth.js";
import { createApiClient } from "./api/client.js";
import { createSkillApi } from "./api/skillApi.js";
import { buildPromptInstallInstruction, triggerFileDownload } from "./distribution.js";
import { ANALYTICS_RANGES, buildAnalyticsParams, formatAnalyticsRange, validateCustomRange } from "./analytics.js";
import { GovernanceSettingsView } from "./GovernanceSettingsView.jsx";
import { CollectionsView } from "./CollectionsView.jsx";
import { AuditExportView } from "./AuditExportView.jsx";
import { OperationsMetricsView } from "./OperationsMetricsView.jsx";
import { hashForRoute, routeFromHash } from "./navigation.js";
import { uploadValidationError } from "./upload.js";
import { SkillMarkdown } from "./SkillMarkdown.jsx";
import { pendingReviewCount, removeReviewById, reviewIdOf } from "./reviewQueue.js";

const apiClient = createApiClient();
const skillApi = createSkillApi(apiClient.request);

function Icon({ name, size = 18, weight = "regular" }) {
  return <i aria-hidden="true" className={`ph ph-${name} ph-${weight}`} style={{ fontSize: size }} />;
}

function Badge({ children, tone = "neutral" }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

function normalizeSkill(skill) {
  const metrics = skill?.metrics ?? {};
  return {
    ...skill,
    version: skill?.version?.startsWith("v") ? skill.version : `v${skill?.version ?? "0.0.0"}`,
    status: skill?.status === "published" ? "稳定" : (skill?.status ?? "未知"),
    rating: metrics.rating ?? 0,
    reviews: metrics.reviews ?? 0,
    calls: metrics.calls ?? 0,
    installs: metrics.installs ?? 0,
    favorites: metrics.favorites ?? 0,
    tags: skill?.tags ?? [],
    capabilities: skill?.capabilities ?? [],
    suitableFor: skill?.suitableFor ?? [],
    unsuitableFor: skill?.unsuitableFor ?? [],
    collection: skill?.collection ?? [],
  };
}

function Toast({ message, onClose }) {
  if (!message) return null;
  return <div className="toast" role="status"><Icon name="check-circle" size={18} weight="fill" /><span>{message}</span><button className="toast-close" onClick={onClose} aria-label="关闭提示"><Icon name="x" size={16} /></button></div>;
}

function NotificationPanel({ notifications, onRead }) {
  return <div className="notification-panel" role="dialog" aria-label="通知列表"><div className="notification-panel-head"><strong>通知</strong><button className="text-button" onClick={() => onRead()}>全部已读</button></div>{notifications.length ? notifications.map((item) => <button className={`notification-item ${item.read ? "read" : ""}`} key={item.id || item.notificationId} onClick={() => onRead(item.id || item.notificationId)}><span className="notification-item-icon"><Icon name={item.icon || "bell"} size={16} /></span><span><strong>{item.title}</strong><small>{item.detail}</small><time>{item.time || item.createdAt || ""}</time></span>{!item.read && <i />}</button>) : <div className="notification-empty">暂无新通知</div>}</div>;
}

function TopBar({ session, view, onNavigate, onLogout, notifications, notificationsOpen, onToggleNotifications, onReadNotifications }) {
  const displayName = session?.displayName || ROLES[session?.role]?.label || "普通开发者";
  return <header className={`topbar ${view === "detail" ? "topbar-dark" : "topbar-light"}`}>
    <button className="brand" onClick={() => onNavigate("market")} aria-label="回到技能市场"><span className="brand-mark"><Icon name="sparkle" size={20} weight="fill" /></span><span>AI Skill 管理中心</span></button>
    <nav className="topnav" aria-label="主导航"><button className={view === "market" || view === "detail" ? "topnav-item active" : "topnav-item"} onClick={() => onNavigate("market")}>技能市场</button><button className={view === "collection" ? "topnav-item active" : "topnav-item"} onClick={() => onNavigate("collection")}>技能合集</button></nav>
    <div className="topbar-actions"><div className="notification-wrap"><button className="icon-button notification-button" aria-label="通知" aria-expanded={notificationsOpen} onClick={onToggleNotifications}><Icon name="bell" size={20} />{notifications.some((item) => !item.read) && <span className="notification-dot">{notifications.filter((item) => !item.read).length}</span>}</button>{notificationsOpen && <NotificationPanel notifications={notifications} onRead={onReadNotifications} />}</div><div className="user-menu"><div className="avatar">{displayName.slice(0, 1)}</div><div className="user-copy"><strong>{displayName}</strong><span>{session?.role === "admin" ? "平台管理" : "研发团队"}</span></div><button className="logout-button" onClick={onLogout}>退出</button></div></div>
  </header>;
}

function LoginView({ onDeveloperEnter, onAdminLogin }) {
  const [loginMode, setLoginMode] = useState(LOGIN_MODES.developer);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const selectMode = (event) => {
    const nextMode = event.target.value;
    setLoginMode(nextMode);
    setError("");
    if (nextMode === LOGIN_MODES.developer) {
      setUsername("");
      setPassword("");
    }
  };
  const submit = (event) => {
    event.preventDefault();
    const session = authenticateAdmin(username.trim(), password);
    if (!session) { setError("管理员账号或密码不正确"); return; }
    setError(""); onAdminLogin(session);
  };
  return <main className="login-shell"><section className="login-card"><div className="login-brand"><span className="brand-mark"><Icon name="sparkle" size={22} weight="fill" /></span><div><strong>AI Skill 管理中心</strong><span>华为开发部门内部 Skill 平台</span></div></div><div className="login-heading"><h1>选择进入方式</h1><p>普通开发者无需账号即可浏览和使用 Skill；平台管理员使用管理员账号进入管理工作台。</p></div><div className="login-entry"><label className="login-field login-mode-field"><span>进入身份</span><select value={loginMode} onChange={selectMode} aria-label="进入身份"><option value={LOGIN_MODES.developer}>普通开发者</option><option value={LOGIN_MODES.admin}>平台管理员</option></select></label>{requiresAdminCredentials(loginMode) ? <form className="login-entry-panel admin-option" onSubmit={submit}><div className="login-option-icon"><Icon name="shield-check" size={24} /></div><div><h2>平台管理员登录</h2><p>登录后可审核、统计、导出和维护平台配置。</p></div><label className="login-field"><span>管理员账号</span><input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" placeholder="请输入账号" /></label><label className="login-field"><span>管理员密码</span><input value={password} onChange={(event) => setPassword(event.target.value)} type="password" autoComplete="current-password" placeholder="请输入密码" /></label>{error && <div className="login-error" role="alert"><Icon name="warning" size={16} />{error}</div>}<button className="primary-action" type="submit">管理员登录</button><small className="login-hint">演示账号：admin / SkillCenter@2026</small></form> : <section className="login-entry-panel developer-option"><div className="login-option-icon"><Icon name="code" size={24} /></div><div><h2>普通开发者</h2><p>无需账号，直接浏览、安装和上传本地 Skill。</p></div><button className="primary-action" onClick={onDeveloperEnter}>直接进入</button></section>}</div></section></main>;
}

function Sidebar({ role, view, onNavigate, onUpload, pendingReviewTotal = 0 }) {
  const allowed = new Set(getNavigationForRole(role));
  return <aside className="sidebar"><div className="sidebar-section">{NAV_ITEMS.slice(0, 2).map((item) => <button key={item.id} className={`sidebar-item ${view === item.id || (view === "detail" && item.id === "market") ? "active" : ""}`} onClick={() => onNavigate(item.id)}><Icon name={item.icon} size={18} /><span>{item.label}</span></button>)}</div><div className="sidebar-label">我的</div><div className="sidebar-section"><button className={`sidebar-item ${view === "favorites" ? "active" : ""}`} onClick={() => onNavigate("favorites")}><Icon name="star" size={18} /><span>我的收藏</span><em>36</em></button><button className={`sidebar-item ${view === "created" ? "active" : ""}`} onClick={() => onNavigate("created")}><Icon name="briefcase" size={18} /><span>我创建的</span><em>12</em></button><button className={`sidebar-item ${view === "installations" ? "active" : ""}`} onClick={() => onNavigate("installations")}><Icon name="download-simple" size={18} /><span>安装记录</span></button><button className={`sidebar-item ${view === "invocations" ? "active" : ""}`} onClick={() => onNavigate("invocations")}><Icon name="pulse" size={18} /><span>调用历史</span></button></div><div className="sidebar-label">管理</div><div className="sidebar-section">{NAV_ITEMS.slice(3).map((item) => allowed.has(item.id) ? <button key={item.id} className={`sidebar-item ${view === item.id ? "active" : ""}`} onClick={() => onNavigate(item.id)}><Icon name={item.icon} size={18} /><span>{item.label}</span>{item.id === "review" && pendingReviewTotal > 0 && <b className="count-badge">{pendingReviewTotal}</b>}</button> : null)}</div><div className="activity-card"><div className="activity-head"><strong>平台动态</strong><button onClick={() => onNavigate("analytics")}>更多 <Icon name="caret-right" size={13} /></button></div>{["新增 Skill：拓扑分析", "EOX 查询 Skill 更新到 v1.2.0", "AI 测试用例生成 Skill 上线", "知识问答 Skill 优化升级", "需求分析 Skill 更新到 v1.3.0"].map((item, index) => <div className="activity-row" key={item}><span className={`activity-dot dot-${index % 4}`} /><span>{item}</span><time>{["08-15", "08-14", "08-13", "08-12", "08-11"][index]}</time></div>)}</div></aside>;
}

function SkillCard({ skill, onOpen }) {
  return <button className="skill-card" onClick={() => onOpen(skill.id)}><div className="skill-card-head"><span className={`skill-icon icon-${skill.iconTone || "blue"}`}><Icon name={skill.icon || "sparkle"} size={25} weight="duotone" /></span><span className="skill-card-title"><strong>{skill.name}</strong><small>{skill.version}</small></span><Badge tone={skill.status === "deprecated" ? "warning" : skill.status === "withdrawn" ? "danger" : "stable"}>{skill.status}</Badge></div><p>{skill.description}</p><div className="skill-tags"><Badge tone="category">{skill.category}</Badge><Badge tone={skill.riskTone || "low"}>{skill.risk}</Badge></div><div className="skill-owner"><span>{skill.team}</span><span className="rating"><Icon name="star" size={15} weight="fill" /> {skill.rating}</span></div><div className="skill-card-foot"><span><Icon name="pulse" size={14} /> {formatMetric(skill.calls)}<small>调用量</small></span><span><Icon name="download-simple" size={14} /> {formatMetric(skill.installs)}<small>安装量</small></span><span><Icon name="star" size={14} /> {formatMetric(skill.favorites)}<small>收藏数</small></span></div></button>;
}

function SkillListRow({ skill, onOpen }) {
  return <button className="skill-list-row" onClick={() => onOpen(skill.id)}><span className={`skill-icon icon-${skill.iconTone || "blue"}`}><Icon name={skill.icon || "sparkle"} size={22} /></span><span className="skill-list-main"><strong>{skill.name}</strong><small>{skill.description}</small></span><Badge tone="category">{skill.category}</Badge><span className="skill-list-stat"><b>{formatMetric(skill.installs)}</b><small>下载</small></span><span className="skill-list-stat"><b>{formatMetric(skill.calls)}</b><small>调用</small></span><span className="skill-list-updated">{skill.lastUpdated}</span><Icon name="caret-right" size={17} /></button>;
}

function MarketView({ skills, total, query, category, status, risk, sort, viewMode, page, totalPages, onQueryChange, onCategory, onStatus, onRisk, onSort, onViewModeChange, onPage, onOpen, onPublish, loading, error }) {
  const categories = ["all", ...new Set(skills.map((skill) => skill.category).filter(Boolean))];
  const pageNumbers = Array.from({ length: totalPages }, (_, index) => index + 1);
  return <main className="content market-content"><div className="market-heading"><div><h1>{formatMarketTitle(total)}</h1><p>发现、安装和使用高质量的 AI 技能，提高工作效率</p></div></div><div className="filterbar"><label className="filter-search"><Icon name="magnifying-glass" size={17} /><input value={query} onChange={(event) => onQueryChange(event.target.value)} placeholder="搜索 Skill 名称 / 描述 / 关键词" aria-label="搜索 Skill" /></label><button className="market-publish-action primary-action" onClick={onPublish}><Icon name="upload-simple" size={16} />发布 Skill</button><select value={category} onChange={(event) => onCategory(event.target.value)} aria-label="按分类筛选"><option value="all">全部分类</option>{categories.slice(1).map((item) => <option value={item} key={item}>{item}</option>)}</select><select value={status} onChange={(event) => onStatus(event.target.value)} aria-label="按状态筛选"><option value="all">全部状态</option><option value="稳定">稳定</option><option value="试用">试用</option></select><select value={risk} onChange={(event) => onRisk(event.target.value)} aria-label="按风险筛选"><option value="all">全部风险等级</option><option value="低风险">低风险</option><option value="中风险">中风险</option></select><div className="filter-spacer" /><select value={sort} onChange={(event) => onSort(event.target.value)} aria-label="排序方式">{MARKET_SORT_OPTIONS.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}</select><button className={`view-toggle ${viewMode === "grid" ? "active" : ""}`} aria-label="网格视图" aria-pressed={viewMode === "grid"} onClick={() => onViewModeChange("grid")}><Icon name="squares-four" size={18} /></button><button className={`view-toggle ${viewMode === "list" ? "active" : ""}`} aria-label="列表视图" aria-pressed={viewMode === "list"} onClick={() => onViewModeChange("list")}><Icon name="list" size={18} /></button></div>{error && <div className="api-state error-state"><Icon name="warning" size={20} />{error}</div>}{loading ? <div className="api-state"><Icon name="spinner" size={20} />正在加载 Skill 目录…</div> : <><div className="result-caption">共 {total} 个 Skill <span>{MARKET_SORT_OPTIONS.find((option) => option.value === sort)?.label}</span></div>{skills.length ? (viewMode === "grid" ? <div className="skill-grid">{skills.map((skill) => <SkillCard key={skill.id} skill={skill} onOpen={onOpen} />)}</div> : <div className="skill-list">{skills.map((skill) => <SkillListRow key={skill.id} skill={skill} onOpen={onOpen} />)}</div>) : <div className="empty-state"><Icon name="magnifying-glass" size={36} /><strong>没有找到匹配的 Skill</strong><span>试试更换关键词或分类。</span></div>}</>}<div className="pagination" aria-label="Skill 分页"><button disabled={page <= 1} onClick={() => onPage(page - 1)} aria-label="上一页"><Icon name="caret-left" size={15} /></button>{pageNumbers.map((pageNumber) => <button key={pageNumber} className={pageNumber === page ? "current" : ""} onClick={() => onPage(pageNumber)}>{pageNumber}</button>)}<button disabled={page >= totalPages} onClick={() => onPage(page + 1)} aria-label="下一页"><Icon name="caret-right" size={15} /></button><span className="pagination-total">每页 12 条</span></div></main>;
}

function StatCard({ icon, tone, label, value, hint }) {
  return <div className="detail-stat"><div className={`stat-icon stat-${tone}`}><Icon name={icon} size={25} weight="duotone" /></div><div><span>{label}</span><strong>{value}</strong><small>{hint}</small></div></div>;
}

function DetailView({ skill, skillContent = "", role, favorite, onBack, onToast, onPromptInstall, onDownload, downloadLoading, onToggleFavorite }) {
  const [activeTab, setActiveTab] = useState("detail");
  useEffect(() => { setActiveTab("detail"); }, [skill?.id]);
  if (!skill) return <main className="content api-state"><Icon name="spinner" size={20} />正在加载 Skill 详情…</main>;
  return <main className="content detail-content">
    <div className="breadcrumbs"><button onClick={onBack}>首页</button><Icon name="caret-right" size={14} /><button onClick={onBack}>技能市场</button><Icon name="caret-right" size={14} /><button>{skill.category}</button><Icon name="caret-right" size={14} /><span>{skill.name}</span></div>
    <div className="detail-layout">
      <div className="detail-main">
        <section className="detail-hero panel"><div className="detail-icon-wrap"><span className={`skill-icon icon-${skill.iconTone || "blue"}`}><Icon name={skill.icon || "sparkle"} size={55} weight="duotone" /></span></div><div className="detail-hero-copy"><h1>{skill.name}</h1><p>{skill.description}。</p><div className="detail-tags">{skill.tags.map((tag, index) => <Badge key={tag} tone={index === 0 ? "category" : "purple"}>{tag}</Badge>)}</div><div className="detail-meta"><span><Icon name="shield-check" size={19} />版本：<b>{skill.version}</b></span><span><Badge tone="success">● Stable</Badge></span><span><Icon name="warning-circle" size={19} />风险等级：<Badge tone={skill.riskTone || "low"}>{skill.risk}</Badge></span><span><Icon name="user" size={19} />负责人：{skill.owner}</span><span><Icon name="calendar-blank" size={19} />最近更新：{skill.lastUpdated}</span></div></div></section>
        <section className="detail-stats"><StatCard icon="star" tone="orange" label="评分" value={skill.rating} hint={`基于 ${skill.reviews} 条评价`} /><StatCard icon="download-simple" tone="blue" label="下载量" value={formatMetric(skill.installs)} hint="总下载次数" /><StatCard icon="chart-line-up" tone="green" label="调用量" value={formatMetric(skill.calls)} hint="总调用次数" /><StatCard icon="heart" tone="purple" label="收藏数" value={formatMetric(skill.favorites)} hint="用户收藏" /></section>
        <section className="detail-body panel">
          <div className="tabs" role="tablist" aria-label="Skill 详情页签"><button type="button" role="tab" aria-selected={activeTab === "detail"} className={activeTab === "detail" ? "active" : ""} onClick={() => setActiveTab("detail")}>详情</button><button type="button" role="tab" aria-selected={activeTab === "versions"} className={activeTab === "versions" ? "active" : ""} onClick={() => setActiveTab("versions")}>版本历史</button><span /></div>
          {activeTab === "detail" ? <SkillMarkdown content={skillContent} /> : <VersionHistoryPanel skill={skill} role={role} onToast={onToast} />}
        </section>
      </div>
      <aside className="detail-aside">
        <section className="operation panel"><h2>操作</h2><button className="primary-action" onClick={onPromptInstall} disabled={skill.status === "withdrawn"}><Icon name="clipboard-text" size={21} />Prompt 快捷安装</button><button className="secondary-action" onClick={onDownload} disabled={skill.status === "withdrawn" || downloadLoading}><Icon name="file-zip" size={21} />{downloadLoading ? "正在准备下载…" : "下载 ZIP"}</button><div className="operation-row"><button onClick={() => onToast("已收藏该 Skill")}><Icon name="star" size={19} />收藏</button><button onClick={() => onToast("分享链接已复制")}><Icon name="share-network" size={19} />分享</button></div></section>
        <section className="collection panel"><h2>所属合集</h2>{skill.collection.map((item) => <button key={item}><span className="collection-icon"><Icon name="circle-wavy-check" size={18} /></span>{item}<Icon name="caret-right" size={16} /></button>)}</section>
        <section className="permission panel"><h2><Icon name="shield-check" size={23} />权限说明</h2><div className="permission-callout"><strong>只读查询权限</strong><p>{skill.permissionSummary || "仅按声明的权限访问内部数据，不修改源系统。"}</p></div><dl><div><dt>发布日期</dt><dd>{skill.publishedAt}</dd></div><div><dt>最近更新</dt><dd>{skill.lastUpdated}</dd></div><div><dt>语言</dt><dd>{skill.language}</dd></div><div><dt>支持语言</dt><dd>{skill.supportedLanguage}</dd></div></dl><div className="side-tags">{skill.tags.map((tag) => <Badge key={tag} tone="category">{tag}</Badge>)}</div></section>
      </aside>
    </div>
    <footer className="footer">© 2026 AI Skill 管理中心，保留所有权利。 <span>服务条款</span><i /> <span>隐私政策</span><i /> <span>联系我们</span></footer>
  </main>;
}

function AnalyticsView({ analytics, analyticsQuery, onAnalyticsQueryChange, analyticsLoading, analyticsError }) {
  const series = analytics?.series ?? [];
  const topSkills = analytics?.topSkills ?? [];
  const maxCalls = Math.max(1, ...series.map((item) => item.calls));
  const customError = analyticsQuery.range === "custom" ? validateCustomRange(analyticsQuery.from, analyticsQuery.to) : "";
  const update = (key, value) => onAnalyticsQueryChange({ ...analyticsQuery, [key]: value });
  return <main className="content analytics-content">
    <div className="page-title-row"><div><h1>技能统计</h1><p>查看平台调用、安装和成功率趋势，支持按团队追踪使用效果。</p></div><button className="secondary-button" disabled><Icon name="download-simple" size={18} />导出报表</button></div>
    <section className="analytics-filter panel">
      <div className="analytics-filter-row"><label><span>时间范围</span><select value={analyticsQuery.range} onChange={(event) => update("range", event.target.value)}>{ANALYTICS_RANGES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label><label><span>Skill</span><input value={analyticsQuery.skillId} onChange={(event) => update("skillId", event.target.value)} placeholder="全部 Skill" /></label><label><span>团队</span><input value={analyticsQuery.teamId} onChange={(event) => update("teamId", event.target.value)} placeholder="全部团队" /></label><label><span>客户端</span><select value={analyticsQuery.clientType} onChange={(event) => update("clientType", event.target.value)}><option value="">全部客户端</option><option value="codex">Codex</option><option value="department-agent">部门 Agent</option><option value="skillmd-compatible">SkillMD</option><option value="gateway">Gateway</option></select></label></div>
      {analyticsQuery.range === "custom" && <div className="analytics-custom-row"><label><span>开始日期</span><input type="date" value={analyticsQuery.from} onChange={(event) => update("from", event.target.value)} /></label><label><span>结束日期</span><input type="date" value={analyticsQuery.to} onChange={(event) => update("to", event.target.value)} /></label><small>{customError || "起止日期均包含，最多 90 天"}</small></div>}
      <div className="analytics-filter-foot"><span>当前范围：{formatAnalyticsRange(analyticsQuery.range, analyticsQuery.from, analyticsQuery.to)}</span>{analyticsLoading && <span className="analytics-loading"><Icon name="spinner" size={14} />正在刷新</span>}</div>
    </section>
    {analyticsError && <div className="api-state error-state"><Icon name="warning" size={18} />{analyticsError}</div>}
    <div className="analytics-kpis"><div><span>范围内调用量</span><strong>{formatMetric(analytics?.kpis?.calls ?? 0)}</strong><small>真实事件聚合</small></div><div><span>调用成功率</span><strong>{analytics?.kpis?.successRate ?? 0}%</strong><small>服务端计算</small></div><div><span>活跃 Skill</span><strong>{analytics?.kpis?.activeSkills ?? 0}</strong><small>有调用事件</small></div><div><span>活跃用户</span><strong>{analytics?.kpis?.activeUsers ?? 0}</strong><small>去重后用户数</small></div><div><span>当前安装</span><strong>{analytics?.kpis?.currentInstallations ?? 0}</strong><small>已安装 / 安装中</small></div><div><span>安装成功率</span><strong>{analytics?.kpis?.installationSuccessRate ?? 0}%</strong><small>按安装请求计算</small></div></div>
    <section className="analytics-panel panel"><div className="section-heading"><div><h2>调用趋势</h2><p>{formatAnalyticsRange(analyticsQuery.range, analyticsQuery.from, analyticsQuery.to)}的全部 Skill 调用量</p></div></div><div className="chart"><div className="chart-y"><span>峰值</span><span>75%</span><span>50%</span><span>25%</span><span>0</span></div><div className="chart-bars">{series.map((item) => <div className="bar-column" key={item.day}><div className="bar-value">{formatMetric(item.calls)}</div><div className="bar" style={{ height: `${(item.calls / maxCalls) * 100}%` }} /><span>{item.day}</span></div>)}</div></div></section>
    <section className="analytics-panel panel"><div className="section-heading"><div><h2>热门 Skill</h2><p>按调用量排序，帮助维护者识别分发重点</p></div></div><div className="usage-table"><div className="usage-row usage-head"><span>Skill</span><span>调用量</span><span>成功率</span><span>数据来源</span></div>{topSkills.length ? topSkills.map((skill) => <div className="usage-row" key={skill.id}><span className="usage-skill"><span className="mini-icon icon-blue"><Icon name="sparkle" size={16} /></span><strong>{skill.name}</strong><small>{skill.id}</small></span><span>{formatMetric(skill.calls)}</span><span className="success-text">{skill.successRate}%</span><span className="trend-up"><Icon name="pulse" size={16} />事件流</span></div>) : <div className="analytics-empty">暂无调用事件</div>}</div></section>
    <div className="analytics-detail-grid"><section className="analytics-panel panel"><div className="section-heading"><div><h2>版本采用</h2><p>按版本查看调用占比</p></div></div>{(analytics?.versionAdoption ?? []).length ? <div className="analytics-list">{analytics.versionAdoption.map((item) => <div className="analytics-list-row" key={`${item.skillId}-${item.version}`}><span>{item.skillId} <small>v{item.version}</small></span><strong>{item.percentage}%</strong><i><em style={{ width: `${item.percentage}%` }} /></i></div>)}</div> : <div className="analytics-empty">暂无版本调用数据</div>}</section><section className="analytics-panel panel"><div className="section-heading"><div><h2>错误与延迟</h2><p>失败原因及响应耗时</p></div></div><div className="analytics-latency"><div><span>p50</span><strong>{analytics?.latency?.p50Ms ?? 0} ms</strong></div><div><span>p95</span><strong>{analytics?.latency?.p95Ms ?? 0} ms</strong></div><div><span>最大</span><strong>{analytics?.latency?.maxMs ?? 0} ms</strong></div></div>{(analytics?.errorBreakdown ?? []).length ? <div className="analytics-errors">{analytics.errorBreakdown.slice(0, 4).map((item) => <span key={item.errorCode}><b>{item.errorCode}</b><em>{item.count}</em></span>)}</div> : <div className="analytics-empty">暂无失败事件</div>}</section></div>
    <section className="analytics-quality panel"><span><Icon name="info" size={16} />数据质量：已接收 {analytics?.dataQuality?.accepted ?? 0}，重复 {analytics?.dataQuality?.duplicates ?? 0}，拒绝 {analytics?.dataQuality?.rejected ?? 0}</span><small>本地运行时口径{analytics?.dataQuality?.hasBackfill ? " · 检测到补报" : ""}</small></section>
  </main>;
}

function ReviewView({ reviews, loading, error, onApprove, onReject }) {
  const [reasons, setReasons] = useState({});
  if (loading) return <main className="content api-state"><Icon name="spinner" size={20} />正在加载待审核 Skill…</main>;
  return <main className="content placeholder-content"><div className="page-title-row"><div><h1>待审核 Skill</h1><p>审核本地上传包，审核通过后才会进入已发布目录。</p></div></div>{error && <div className="api-state error-state"><Icon name="warning" size={20} />{error}</div>}<div className="governance-list">{reviews.length === 0 ? <div className="empty-state"><Icon name="check-circle" size={32} />当前没有待审核任务</div> : reviews.map((review) => { const id = reviewIdOf(review); return <article className="governance-row panel" key={id}><div><strong>{review.skillId}</strong><span>{review.version} · {review.submittedBy}</span></div><Badge tone="warning">{review.status}</Badge><input aria-label={`驳回原因 ${id}`} value={reasons[id] || ""} onChange={(event) => setReasons({ ...reasons, [id]: event.target.value })} placeholder="驳回原因（驳回时必填）" /><div className="governance-actions"><button className="primary-action" onClick={() => onApprove(id)}>通过</button><button className="secondary-button" disabled={!reasons[id]?.trim()} onClick={() => onReject(id, reasons[id])}>驳回</button></div></article>; })}</div></main>;
}

function InstallationsView({ installations, loading, error }) {
  if (loading) return <main className="content api-state"><Icon name="spinner" size={20} />正在加载安装记录…</main>;
  return <main className="content placeholder-content"><div className="page-title-row"><div><h1>安装记录</h1><p>查看当前用户或组织内的 Skill 分发记录。</p></div></div>{error && <div className="api-state error-state"><Icon name="warning" size={20} />{error}</div>}<div className="governance-list">{installations.length === 0 ? <div className="empty-state"><Icon name="download-simple" size={32} />暂无安装记录</div> : installations.map((installation) => <article className="governance-row panel installation-row" key={installation.installationId}><div><strong>{installation.skillId}</strong><span>{installation.version} · {installation.clientType} {installation.clientVersion}</span></div><Badge tone={installationStatusTone(installation.status)}>{installation.status}</Badge><time>{installation.installedAt ? `已安装 ${installation.installedAt}` : `请求于 ${installation.requestedAt}`}</time><span>{installation.method || "one-click"}{installation.lastErrorCode ? ` · ${installation.lastErrorCode}` : ""}</span></article>)}</div></main>;
}

function PersonalSkillsView({ title, items, loading, error, onOpen }) {
  if (loading) return <main className="content api-state"><Icon name="spinner" size={20} />正在加载个人数据…</main>;
  return <main className="content placeholder-content"><div className="page-title-row"><div><h1>{title}</h1><p>数据绑定当前登录用户，不展示其他用户的个人记录。</p></div></div>{error && <div className="api-state error-state"><Icon name="warning" size={20} />{error}</div>}<div className="skill-grid personal-skill-grid">{items.length ? items.map((skill) => <button className="skill-card" key={skill.id} onClick={() => onOpen(skill.id)}><div className="skill-card-head"><span className="skill-icon icon-blue"><Icon name={skill.icon || "sparkle"} size={25} /></span><span className="skill-card-title"><strong>{skill.name || skill.id}</strong><small>{skill.version || "—"}</small></span><Badge tone={skill.status === "withdrawn" ? "danger" : skill.status === "deprecated" ? "warning" : "stable"}>{skill.status}</Badge></div><p>{skill.description || "暂无描述"}</p><div className="skill-owner"><span>{skill.team || skill.owner || "—"}</span></div></button>) : <div className="empty-state"><Icon name="star" size={36} /><strong>暂无数据</strong><span>完成一次收藏、上传或分发后，这里会显示真实记录。</span></div>}</div></main>;
}

function InvocationsView({ page, loading, error, onPage }) {
  if (loading) return <main className="content api-state"><Icon name="spinner" size={20} />正在加载调用历史…</main>;
  const items = page?.items ?? [];
  return <main className="content placeholder-content"><div className="page-title-row"><div><h1>调用历史</h1><p>仅显示当前用户的调用摘要，不包含提示词、输出、设备或 token。</p></div></div>{error && <div className="api-state error-state"><Icon name="warning" size={20} />{error}</div>}<div className="governance-list">{items.length ? items.map((item, index) => <article className="governance-row panel" key={`${item.occurredAt}-${item.skillId}-${index}`}><div><strong>{item.skillId}</strong><span>{item.version || "—"} · {item.clientType || "—"} {item.clientVersion || ""}</span></div><Badge tone={item.status === "success" ? "success" : "warning"}>{item.status}</Badge><time>{item.occurredAt}</time><span>{item.durationMs} ms{item.errorCode ? ` · ${item.errorCode}` : ""}</span></article>) : <div className="empty-state"><Icon name="pulse" size={36} /><strong>暂无调用历史</strong><span>当前进程还没有属于你的调用事件。</span></div>}</div>{(page?.total ?? 0) > (page?.pageSize ?? 20) && <div className="pagination"><button disabled={page.page <= 1} onClick={() => onPage(page.page - 1)}>上一页</button><span>{page.page} / {Math.ceil(page.total / page.pageSize)}</span><button disabled={page.page >= Math.ceil(page.total / page.pageSize)} onClick={() => onPage(page.page + 1)}>下一页</button></div>}</main>;
}

function VersionHistoryPanel({ skill, role, onToast }) {
  const [versions, setVersions] = useState([]);
  const [selected, setSelected] = useState(null);
  const [reason, setReason] = useState("");
  const [replacementVersion, setReplacementVersion] = useState("");
  const [impact, setImpact] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const load = () => skillApi.listVersions(skill.id).then((response) => setVersions(response.data ?? [])).catch((loadError) => setError(loadError.message || "版本历史加载失败"));
  useEffect(() => { if (skill?.id) load(); }, [skill?.id]);
  const submit = async (action) => {
    if (!reason.trim()) { setError("请填写生命周期原因"); return; }
    setLoading(true); setError("");
    try {
      if (action === "withdraw") setImpact((await skillApi.getVersionImpact(skill.id, selected.version)).data);
      const api = action === "withdraw" ? skillApi.withdrawVersion : skillApi.deprecateVersion;
      await api(skill.id, selected.version, { reason: reason.trim(), replacementVersion: replacementVersion.trim() || undefined });
      setSelected(null); setReason(""); setReplacementVersion(""); setImpact(null); await load(); onToast(action === "withdraw" ? "版本已下架" : "版本已废弃");
    } catch (submitError) { setError(submitError.message || "生命周期操作失败"); }
    finally { setLoading(false); }
  };
  return <section className="version-history panel"><div className="section-heading"><div><h2>版本历史</h2><p>普通用户可见已发布和已废弃版本，管理员可执行生命周期操作。</p></div></div>{error && <div className="api-state error-state">{error}</div>}<div className="governance-list">{versions.length ? versions.map((version) => <article className="governance-row" key={`${version.skillId}-${version.version}`}><div><strong>{version.version}</strong><span>{version.statusReason || "无生命周期备注"}</span></div><Badge tone={version.status === "withdrawn" ? "danger" : version.status === "deprecated" ? "warning" : "stable"}>{version.status}</Badge><span>{version.replacementVersion ? `替代 ${version.replacementVersion}` : ""}</span>{role === "admin" && version.status !== "withdrawn" && <div className="governance-actions"><button className="secondary-button" onClick={() => { setSelected(version); setReason(""); setReplacementVersion(version.replacementVersion || ""); }}>生命周期操作</button></div>}</article>) : <div className="empty-state">暂无版本历史</div>}</div>{selected && <div className="modal-backdrop" role="presentation"><section className="upload-modal" role="dialog" aria-modal="true"><div className="modal-head"><div><h2>更新 {selected.version} 生命周期</h2><p>废弃可继续分发；下架会立即阻止新的授权、Manifest 和制品下载。</p></div><button className="icon-button" onClick={() => setSelected(null)} aria-label="关闭"><Icon name="x" size={20} /></button></div>{impact && <div className="placeholder-note">影响面：{impact.installationCount} 条安装记录，{impact.activeInstallationCount} 条活跃安装，{impact.invocationCount} 次调用。</div>}<label className="distribution-field"><span>原因（必填）</span><textarea value={reason} onChange={(event) => setReason(event.target.value)} /></label><label className="distribution-field"><span>替代版本（可选）</span><input value={replacementVersion} onChange={(event) => setReplacementVersion(event.target.value)} placeholder="例如 1.1.0" /></label><div className="modal-actions"><button className="secondary-button" onClick={() => setSelected(null)}>取消</button><button className="secondary-button" disabled={loading} onClick={() => submit("deprecate")}>废弃版本</button><button className="primary-action" disabled={loading} onClick={() => submit("withdraw")}>下架版本</button></div></section></div>}</section>;
}

function PlaceholderView({ view, onUpload, onOpen }) {
  const [items, setItems] = useState([]);
  const [page, setPage] = useState({ items: [], page: 1, pageSize: 20, total: 0 });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!["favorites", "created", "invocations"].includes(view)) return;
    setLoading(true); setError("");
    const request = view === "favorites" ? skillApi.listFavorites() : view === "created" ? skillApi.listMySkills() : skillApi.listMyInvocations({ page: page.page, pageSize: page.pageSize });
    request.then((response) => view === "invocations" ? setPage(response.data ?? {}) : setItems(response.data ?? []))
      .catch((loadError) => setError(loadError.message || "个人数据加载失败"))
      .finally(() => setLoading(false));
  }, [view, page.page]);
  if (view === "favorites") return <PersonalSkillsView title="我的收藏" items={items} loading={loading} error={error} onOpen={onOpen} />;
  if (view === "created") return <PersonalSkillsView title="我创建的" items={items} loading={loading} error={error} onOpen={onOpen} />;
  if (view === "invocations") return <InvocationsView page={page} loading={loading} error={error} onPage={(nextPage) => setPage((current) => ({ ...current, page: nextPage }))} />;
  const content = { collection: ["技能合集", "把常用能力按业务场景组织起来，方便团队一键分发。"], "my-skills": ["我的 Skill", "这里会展示当前用户负责或上传的 Skill。"], settings: ["标签管理", "维护平台分类、标签和风险等级字典。"] }[view] ?? ["工作台", "选择左侧菜单开始管理 Skill。"];
  return <main className="content placeholder-content"><div className="placeholder-icon"><Icon name={view === "review" ? "clipboard-text" : "squares-four"} size={34} /></div><h1>{content[0]}</h1><p>{content[1]}</p>{view === "created" && <button className="primary-action inline-action" onClick={onUpload}><Icon name="upload-simple" size={19} />上传本地 Skill ZIP</button>}<div className="placeholder-note"><Icon name="info" size={18} />页面已连接 Skill Center API，更多组织管理能力按里程碑开放。</div></main>;
}

function UploadModal({ onClose, onUpload }) {
  const [file, setFile] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const submit = async () => {
    if (!file) return;
    setSubmitting(true);
    setError("");
    try {
      await onUpload(file);
      onClose();
    } catch (uploadError) {
      setError(uploadError.message || "上传校验失败");
    } finally {
      setSubmitting(false);
    }
  };
  return <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><section className="upload-modal" role="dialog" aria-modal="true" aria-labelledby="upload-title"><div className="modal-head"><div><h2 id="upload-title">发布 Skill</h2><p>Skill 需从本地开发环境以 ZIP 包上传，发布后进入审核。</p></div><button className="icon-button" onClick={onClose} aria-label="关闭"><Icon name="x" size={20} /></button></div><label className="dropzone"><Icon name="upload-simple" size={32} /><strong>{file?.name || "选择 Skill ZIP 包"}</strong><span>包含 SKILL.md（skill.json 可选）</span><input type="file" accept=".zip" onChange={(event) => { setFile(event.target.files?.[0] || null); setError(""); }} /></label>{error && <div className="upload-error" role="alert"><Icon name="warning-circle" size={16} weight="fill" />{error}</div>}<div className="upload-rules"><span><Icon name="check-circle" size={16} weight="fill" />自动校验目录结构</span><span><Icon name="check-circle" size={16} weight="fill" />校验权限与依赖</span><span><Icon name="check-circle" size={16} weight="fill" />发布后进入审核</span></div><div className="modal-actions"><button className="secondary-button" onClick={onClose}>取消</button><button className="primary-action" disabled={!file || submitting} onClick={submit}>{submitting ? "正在校验…" : "开始校验并发布"}</button></div></section></div>;
}

export function App() {
  const [session, setSession] = useState(() => readSession());
  const [view, setView] = useState(() => routeFromHash(typeof window === "undefined" ? "" : window.location.hash).view);
  const [selectedId, setSelectedId] = useState(() => routeFromHash(typeof window === "undefined" ? "" : window.location.hash).selectedId);
  const [selectedSkill, setSelectedSkill] = useState(null);
  const [skillContent, setSkillContent] = useState("");
  const [skills, setSkills] = useState([]);
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("all");
  const [marketStatus, setMarketStatus] = useState("all");
  const [marketRisk, setMarketRisk] = useState("all");
  const [marketSort, setMarketSort] = useState("updated");
  const [marketViewMode, setMarketViewMode] = useState("grid");
  const [marketPage, setMarketPage] = useState(1);
  const [marketTotal, setMarketTotal] = useState(0);
  const [analytics, setAnalytics] = useState(null);
  const [analyticsQuery, setAnalyticsQuery] = useState({ range: "7d", from: "", to: "", skillId: "", teamId: "", clientType: "" });
  const [analyticsLoading, setAnalyticsLoading] = useState(false);
  const [analyticsError, setAnalyticsError] = useState("");
  const [reviews, setReviews] = useState([]);
  const [pendingReviewTotal, setPendingReviewTotal] = useState(0);
  const [installations, setInstallations] = useState([]);
  const [favoriteSkillIds, setFavoriteSkillIds] = useState(new Set());
  const [governanceLoading, setGovernanceLoading] = useState(false);
  const [governanceError, setGovernanceError] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [uploadOpen, setUploadOpen] = useState(false);
  const [downloadLoading, setDownloadLoading] = useState(false);
  const [toast, setToast] = useState("");
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [notifications, setNotifications] = useState([]);
  const loadSequence = useRef(0);
  const showToast = (message) => { setToast(message); window.clearTimeout(window.__skillToastTimer); window.__skillToastTimer = window.setTimeout(() => setToast(""), 3200); };
  const role = session?.role ?? "developer";
  useEffect(() => {
    const restoreRoute = () => {
      const nextRoute = routeFromHash(window.location.hash);
      setView(nextRoute.view);
      setSelectedId(nextRoute.selectedId);
      setSelectedSkill(null);
      setNotificationsOpen(false);
    };
    window.addEventListener("popstate", restoreRoute);
    window.addEventListener("hashchange", restoreRoute);
    if (!window.location.hash) window.history.replaceState({ view: "market" }, "", hashForRoute({ view: "market" }));
    return () => {
      window.removeEventListener("popstate", restoreRoute);
      window.removeEventListener("hashchange", restoreRoute);
    };
  }, []);
  useEffect(() => { if (session) apiClient.setActor({ userId: session.userId, role: ROLES[session.role]?.actorRole || "developer" }); }, [session]);
  useEffect(() => {
    if (!session) return;
    skillApi.listFavorites().then((response) => setFavoriteSkillIds(new Set((response.data ?? []).map((item) => item.id)))).catch(() => setFavoriteSkillIds(new Set()));
  }, [session]);
  useEffect(() => {
    if (!session) return;
    skillApi.listNotifications()
      .then((response) => setNotifications((response.data?.items ?? []).map((item) => ({ ...item, id: item.id || item.notificationId }))))
      .catch(() => setNotifications([]));
  }, [session]);
  const loadSkills = async (requestedPage = marketPage) => { const requestId = ++loadSequence.current; setLoading(true); try { const response = await skillApi.listSkills({ query, category, status: marketStatus === "稳定" ? "published" : marketStatus, risk: marketRisk, sort: marketSort, page: requestedPage, pageSize: 12 }); if (requestId !== loadSequence.current) return; const nextSkills = (response.data?.items ?? []).map(normalizeSkill); setSkills(nextSkills); setMarketTotal(Number(response.data?.total ?? nextSkills.length)); if (!selectedId && nextSkills[0]) setSelectedId(nextSkills[0].id); setError(""); } catch (loadError) { if (requestId === loadSequence.current) setError(loadError.message || "Skill 目录加载失败"); } finally { if (requestId === loadSequence.current) setLoading(false); } };
  useEffect(() => { if (session) loadSkills(marketPage); }, [query, category, marketStatus, marketRisk, marketSort, marketPage, session]);
  useEffect(() => {
    if (!session) return;
    const validation = analyticsQuery.range === "custom" ? validateCustomRange(analyticsQuery.from, analyticsQuery.to) : "";
    if (validation) {
      setAnalyticsError(validation);
      return;
    }
    setAnalyticsLoading(true);
    setAnalyticsError("");
    skillApi.getAnalyticsOverview(buildAnalyticsParams(analyticsQuery.range, analyticsQuery.from, analyticsQuery.to, analyticsQuery))
      .then((response) => setAnalytics(response.data))
      .catch((loadError) => setAnalyticsError(loadError.message || "统计数据加载失败"))
      .finally(() => setAnalyticsLoading(false));
  }, [analyticsQuery.range, analyticsQuery.from, analyticsQuery.to, analyticsQuery.skillId, analyticsQuery.teamId, analyticsQuery.clientType, session]);
  useEffect(() => {
    if (!session || !selectedId || view !== "detail") return undefined;
    let active = true;
    setSkillContent("");
    skillApi.getSkill(selectedId)
      .then((response) => { if (active) setSelectedSkill(normalizeSkill(response.data)); })
      .catch((detailError) => {
        if (!active) return;
        setError(detailError.message || "Skill 详情加载失败");
        navigate("market", { replace: true });
      });
    skillApi.getSkillContent(selectedId)
      .then((response) => { if (active) setSkillContent(typeof response.data === "string" ? response.data : ""); })
      .catch(() => { if (active) setSkillContent(""); });
    return () => { active = false; };
  }, [selectedId, view, session]);
  useEffect(() => {
    if (view !== "review" && view !== "installations") return;
    setGovernanceLoading(true);
    setGovernanceError("");
    const request = view === "review" ? skillApi.listReviews() : skillApi.listMyInstallations();
    request.then((response) => {
      const items = response.data ?? [];
      if (view === "review") {
        setReviews(items);
        setPendingReviewTotal(pendingReviewCount(items));
      } else {
        setInstallations(items);
      }
    })
      .catch((loadError) => setGovernanceError(loadError.message || "治理数据加载失败"))
      .finally(() => setGovernanceLoading(false));
  }, [view, role]);
  useEffect(() => {
    if (!session || role !== "admin" || view === "review") return undefined;
    let active = true;
    skillApi.listReviews()
      .then((response) => { if (active) setPendingReviewTotal(pendingReviewCount(response.data ?? [])); })
      .catch(() => { if (active) setPendingReviewTotal(0); });
    return () => { active = false; };
  }, [session, role, view]);
  const totalMarketPages = Math.max(1, Math.ceil(marketTotal / 12));
  const visibleSkills = skills;
  useEffect(() => { if (marketPage > totalMarketPages) setMarketPage(totalMarketPages); }, [marketPage, totalMarketPages]);
  const commitRoute = (nextView, nextSelectedId = null, { replace = false } = {}) => {
    const nextHash = hashForRoute({ view: nextView, selectedId: nextSelectedId });
    if (window.location.hash !== nextHash) {
      const method = replace ? "replaceState" : "pushState";
      window.history[method]({ view: nextView, selectedId: nextSelectedId }, "", nextHash);
    }
    setView(nextView);
    setSelectedId(nextSelectedId);
    if (nextView !== "detail") setSelectedSkill(null);
  };
  const navigate = (nextView, options = {}) => { if (nextView === "upload") { setUploadOpen(true); return; } if (nextView === "operations" && role !== "admin") { commitRoute("market", null, { replace: true }); return; } commitRoute(nextView, null, options); };
  const openSkill = (id) => { commitRoute("detail", id); window.scrollTo({ top: 0, behavior: "smooth" }); };
  const copyPromptInstall = async () => {
    const instruction = buildPromptInstallInstruction(selectedSkill);
    try {
      if (!navigator.clipboard?.writeText) throw new Error("当前浏览器不支持剪贴板");
      await navigator.clipboard.writeText(instruction);
      showToast("安装指引已复制，可粘贴到 Agent 对话中");
    } catch {
      showToast("安装指引复制失败，请手动复制");
    }
  };
  const downloadZip = async () => {
    if (!selectedId || !selectedSkill || downloadLoading) return;
    setDownloadLoading(true);
    try {
      const response = await skillApi.createInstallation(selectedId, { clientType: "codex", clientVersion: "1.0.0", method: "manual-zip" });
      const data = response.data;
      const url = data?.authorization?.downloadUrl || data?.artifact?.downloadUrl;
      if (!url) throw new Error("下载授权未返回文件地址");
      const version = String(selectedSkill.version || "latest").replace(/^v/, "");
      if (!triggerFileDownload(url, `${selectedId}-${version}.zip`)) throw new Error("当前浏览器无法触发下载");
      showToast("ZIP 下载已开始");
    } catch (downloadError) {
      showToast(downloadError.message || "ZIP 下载失败");
    } finally {
      setDownloadLoading(false);
    }
  };
  const upload = async (file) => {
    const response = await skillApi.uploadPackage(file);
    const validationError = uploadValidationError(response.data);
    if (validationError) throw new Error(validationError);
    showToast(`上传校验完成：${response.data?.status || "validated"}`);
    setMarketPage(1);
    await loadSkills(1);
  };
  const approveReview = async (reviewId) => { try { await skillApi.approveReview(reviewId); setReviews((items) => removeReviewById(items, reviewId)); setPendingReviewTotal((count) => Math.max(0, count - 1)); showToast("Skill 已发布"); } catch (approveError) { showToast(approveError.message || "审核通过失败"); } };
  const rejectReview = async (reviewId, reason) => { try { await skillApi.rejectReview(reviewId, reason); setReviews((items) => removeReviewById(items, reviewId)); setPendingReviewTotal((count) => Math.max(0, count - 1)); showToast("Skill 已驳回"); } catch (rejectError) { showToast(rejectError.message || "驳回失败"); } };
  const toggleFavorite = async () => {
    if (!selectedId) return;
    const wasFavorite = favoriteSkillIds.has(selectedId);
    setFavoriteSkillIds((current) => { const next = new Set(current); wasFavorite ? next.delete(selectedId) : next.add(selectedId); return next; });
    try {
      if (wasFavorite) await skillApi.removeFavorite(selectedId); else await skillApi.addFavorite(selectedId);
      showToast(wasFavorite ? "已取消收藏" : "已收藏该 Skill");
    } catch (favoriteError) {
      setFavoriteSkillIds((current) => { const next = new Set(current); wasFavorite ? next.add(selectedId) : next.delete(selectedId); return next; });
      showToast(favoriteError.message || "收藏操作失败");
    }
  };
  const handleDetailToast = (message) => { if (message === "已收藏该 Skill") { toggleFavorite(); } else showToast(message); };
  if (!session) return <LoginView onDeveloperEnter={() => { const next = createDeveloperSession(); saveSession(next); setSession(next); }} onAdminLogin={(next) => { saveSession(next); setSession(next); }} />;
  const readNotifications = async (id) => {
    try {
      if (id) await skillApi.readNotification(id); else await skillApi.readAllNotifications();
      setNotifications((items) => id
        ? items.map((item) => (item.id || item.notificationId) === id ? { ...item, read: true } : item)
        : items.map((item) => ({ ...item, read: true })));
    } catch (readError) {
      showToast(readError.message || "通知状态更新失败");
    }
  };
  const logout = () => { clearSession(); setSession(null); setNotificationsOpen(false); commitRoute("market", null, { replace: true }); };
  return <div className="app-shell"><TopBar session={session} view={view} onNavigate={navigate} onLogout={logout} notifications={notifications} notificationsOpen={notificationsOpen} onToggleNotifications={() => setNotificationsOpen((open) => !open)} onReadNotifications={(id) => { readNotifications(id); if (id) setNotificationsOpen(false); }} /><div className="workspace">{view !== "detail" && <Sidebar role={role} view={view} onNavigate={navigate} onUpload={() => setUploadOpen(true)} pendingReviewTotal={pendingReviewTotal} />}{view === "market" && <MarketView skills={visibleSkills} total={marketTotal} query={query} category={category} status={marketStatus} risk={marketRisk} sort={marketSort} viewMode={marketViewMode} page={marketPage} totalPages={totalMarketPages} onQueryChange={(nextQuery) => { setQuery(nextQuery); setMarketPage(1); navigate("market", { replace: true }); }} onCategory={(nextCategory) => { setCategory(nextCategory); setMarketPage(1); }} onStatus={(nextStatus) => { setMarketStatus(nextStatus); setMarketPage(1); }} onRisk={(nextRisk) => { setMarketRisk(nextRisk); setMarketPage(1); }} onSort={(nextSort) => { setMarketSort(nextSort); setMarketPage(1); }} onViewModeChange={setMarketViewMode} onPage={setMarketPage} onOpen={openSkill} onPublish={() => setUploadOpen(true)} loading={loading} error={error} />}{view === "detail" && <DetailView skill={selectedSkill} skillContent={skillContent} role={role} favorite={favoriteSkillIds.has(selectedId)} onBack={() => navigate("market")} onToast={handleDetailToast} onToggleFavorite={toggleFavorite} onPromptInstall={copyPromptInstall} onDownload={downloadZip} downloadLoading={downloadLoading} />}{view === "analytics" && <AnalyticsView analytics={analytics} analyticsQuery={analyticsQuery} onAnalyticsQueryChange={setAnalyticsQuery} analyticsLoading={analyticsLoading} analyticsError={analyticsError} />}{view === "review" && <ReviewView reviews={reviews} loading={governanceLoading} error={governanceError} onApprove={approveReview} onReject={rejectReview} />}{view === "installations" && <InstallationsView installations={installations} loading={governanceLoading} error={governanceError} />}{(view === "collection" || view === "my-skills") && <CollectionsView api={skillApi} role={role} onOpenSkill={openSkill} onToast={showToast} />}{view === "settings" && <GovernanceSettingsView api={skillApi} role={role} onToast={showToast} />}{view === "exports" && <AuditExportView api={skillApi} role={role} onToast={showToast} />}{view === "operations" && <OperationsMetricsView api={skillApi} role={role} />}{!["market", "detail", "analytics", "review", "installations", "collection", "my-skills", "settings", "exports", "operations"].includes(view) && <PlaceholderView view={view} onUpload={() => setUploadOpen(true)} onOpen={openSkill} />}</div><Toast message={toast} onClose={() => setToast("")} />{uploadOpen && <UploadModal onClose={() => setUploadOpen(false)} onUpload={upload} />}</div>;
}
