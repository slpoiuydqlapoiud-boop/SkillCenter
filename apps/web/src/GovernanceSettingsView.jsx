import { useEffect, useMemo, useState } from "react";
import { formatConfigError, validateGovernanceForm } from "./governance.js";

const TABS = ["teams", "roles", "taxonomy", "collections", "policy"];

export function GovernanceSettingsView({ api, role, onToast }) {
  const admin = role === "admin";
  const [tab, setTab] = useState("teams");
  const [data, setData] = useState({ teams: [], roleBindings: [], categories: [], tags: [], collections: [], policy: null });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const [taxonomyKind, setTaxonomyKind] = useState("category");
  const [policyForm, setPolicyForm] = useState({ pageSizeOptions: "12,24,48", maxPageSize: 48, minimumClientVersion: "1.0.0", defaultCollectionVisibility: "public" });
  const [message, setMessage] = useState("");
  const load = async () => {
    setLoading(true); setError("");
    try {
      if (admin) {
        const [teams, roles, categories, tags, collections, policy] = await Promise.all([
          api.listTeams(), api.listRoleBindings(), api.listCategories(), api.listTags(), api.listCollections(), api.getPolicy(),
        ]);
        setData({ teams: teams.data ?? [], roleBindings: roles.data ?? [], categories: categories.data ?? [], tags: tags.data ?? [], collections: collections.data ?? [], policy: policy.data });
      } else {
        const taxonomy = await api.getTaxonomy();
        const value = taxonomy.data ?? {};
        setData((current) => ({ ...current, categories: value.categories ?? [], tags: value.tags ?? [], collections: value.collections ?? [], policy: value.platformPolicy }));
      }
    } catch (loadError) { setError(formatConfigError(loadError)); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [role]);
  const items = useMemo(() => tab === "policy" ? [] : tab === "taxonomy"
    ? [...(data.categories ?? []).map((item) => ({ ...item, kind: "分类" })), ...(data.tags ?? []).map((item) => ({ ...item, kind: "标签" }))]
    : data[tab] ?? [], [data, tab]);
  const save = async () => {
    if (!admin) return;
    const validation = validateGovernanceForm({ code, name }, tab === "policy" ? [] : tab === "taxonomy" ? ["code", "name"] : ["name"]);
    if (Object.keys(validation).length) { setMessage(Object.values(validation)[0]); return; }
    try {
      if (tab === "teams") await api.saveTeam(null, { teamId: code || name.toLowerCase().replace(/\s+/g, "-"), name, description: "", ownerUserId: "local-user", memberUserIds: ["local-user"] });
      if (tab === "taxonomy") {
        const payload = { code, displayName: name, description: "", sortOrder: 0 };
        if (taxonomyKind === "tag") await api.saveTag(null, payload); else await api.saveCategory(null, payload);
      }
      if (tab === "collections") await api.saveCollection(null, { collectionId: code, name, description: "", ownerTeamId: null, visibility: "public", skillIds: [], sortOrder: 0 });
      if (tab === "policy") await api.savePolicy({ ...policyForm, pageSizeOptions: policyForm.pageSizeOptions.split(",").map((item) => Number(item.trim())).filter(Boolean), maxPageSize: Number(policyForm.maxPageSize) });
      setName(""); setCode(""); setMessage(""); onToast?.("配置已保存"); await load();
    } catch (saveError) { setMessage(formatConfigError(saveError)); }
  };
  if (loading) return <main className="content api-state">正在加载治理配置…</main>;
  return <main className="content placeholder-content governance-settings"><div className="page-title-row"><div><h1>平台治理配置</h1><p>维护团队、角色、分类、标签、合集和分页策略。Skill 正文仍只允许从本地 ZIP 上传。</p></div></div><div className="settings-tabs">{TABS.map((item) => <button className={tab === item ? "active" : ""} key={item} onClick={() => setTab(item)}>{({ teams: "团队", roles: "角色", taxonomy: "分类与标签", collections: "合集", policy: "平台策略" })[item]}</button>)}</div>{error && <div className="api-state error-state">{error}</div>}{message && <div className="placeholder-note">{message}</div>}{tab === "policy" ? <section className="panel governance-form"><div><label>分页选项<input value={policyForm.pageSizeOptions} disabled={!admin} onChange={(event) => setPolicyForm({ ...policyForm, pageSizeOptions: event.target.value })} /></label><label>分页上限<input type="number" value={policyForm.maxPageSize} disabled={!admin} onChange={(event) => setPolicyForm({ ...policyForm, maxPageSize: event.target.value })} /></label><label>最低客户端版本<input value={policyForm.minimumClientVersion} disabled={!admin} onChange={(event) => setPolicyForm({ ...policyForm, minimumClientVersion: event.target.value })} /></label></div>{admin && <button className="primary-action" onClick={save}>保存策略 v{(data.policy?.policyVersion || 1) + 1}</button>}</section> : <><section className="panel governance-form"><div>{tab === "taxonomy" && <label>类型<select value={taxonomyKind} disabled={!admin} onChange={(event) => setTaxonomyKind(event.target.value)}><option value="category">分类</option><option value="tag">标签</option></select></label>}<label>{tab === "taxonomy" ? "编码" : "名称"}<input value={tab === "taxonomy" ? code : name} disabled={!admin} onChange={(event) => tab === "taxonomy" ? setCode(event.target.value) : setName(event.target.value)} /></label>{tab === "taxonomy" && <label>显示名称<input value={name} disabled={!admin} onChange={(event) => setName(event.target.value)} /></label>}</div>{admin && <button className="primary-action" onClick={save}>新增</button>}</section><section className="governance-list">{items.length ? items.map((item) => <article className="governance-row panel" key={`${item.kind || tab}-${item.teamId || item.userId || item.code || item.collectionId}`}><div><strong>{item.name || item.displayName || item.userId || item.collectionId}</strong><span>{item.kind || item.status || item.role || item.visibility || "active"}</span></div><span>{item.description || item.teamId || ""}</span></article>) : <div className="empty-state">暂无配置</div>}</section></>}</main>;
}
