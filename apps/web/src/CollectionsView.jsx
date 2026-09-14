import { useEffect, useMemo, useRef, useState } from "react";
import { EXAMPLE_COLLECTIONS, MARKET_SORT_OPTIONS, SKILLS, getCollectionSkillCount } from "./state.js";
import { formatConfigError } from "./governance.js";
import { useDialogKeyboard } from "./accessibility.js";

function Icon({ name, size = 18, weight = "regular" }) {
  return <i aria-hidden="true" className={`ph ph-${name} ph-${weight}`} style={{ fontSize: size }} />;
}

function exampleSkills(collection) {
  return SKILLS.filter((skill) => collection.skillIds?.includes(skill.id));
}

function CollectionListRow({ collection, onOpen }) {
  const skillCount = getCollectionSkillCount(collection);
  return <button className="skill-list-row collection-list-row" onClick={() => onOpen(collection)}><span className="skill-icon icon-purple"><span aria-hidden="true">✦</span></span><span className="skill-list-main"><strong>{collection.name}</strong><small>{collection.description || "暂无描述"}</small></span><span className="badge badge-category">公开合集</span><span className="skill-list-stat"><b>{skillCount}</b><small>包含 Skill</small></span><span className="skill-list-stat"><b>{collection.ownerTeamId || "平台运营"}</b><small>维护团队</small></span><span className="skill-list-updated">{collection.lastUpdated || collection.updatedAt || "最近更新"}</span><Icon name="caret-right" size={17} /></button>;
}

export function CollectionsView({ api, role, onOpenSkill, onToast }) {
  const [collections, setCollections] = useState([]);
  const [selected, setSelected] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [createName, setCreateName] = useState("");
  const [createDescription, setCreateDescription] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState("");
  const dialogRef = useRef(null);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState("updated");
  const [viewMode, setViewMode] = useState("grid");
  const [page, setPage] = useState(1);
  const [collectionTotal, setCollectionTotal] = useState(0);
  const pageSize = 12;
  const loadCollections = (requestedPage = page) => {
    setLoading(true); setError("");
    return api.listPublicCollections({ query, sort, page: requestedPage, pageSize: pageSize })
      .then((response) => {
        const backendItems = response.data?.items ?? [];
        const backendTotal = Number(response.data?.total ?? backendItems.length);
        const useExamples = backendTotal === 0 && requestedPage === 1;
        setCollections(useExamples ? EXAMPLE_COLLECTIONS : backendItems);
        setCollectionTotal(useExamples ? EXAMPLE_COLLECTIONS.length : backendTotal);
      })
      .catch((loadError) => { setCollections(EXAMPLE_COLLECTIONS); setCollectionTotal(EXAMPLE_COLLECTIONS.length); setPage(1); setError(formatConfigError(loadError)); })
      .finally(() => setLoading(false));
  };
  useEffect(() => { loadCollections(page).catch(() => {}); }, [api, query, sort, page]);
  const open = async (collection) => {
    const fallback = { collection, skills: exampleSkills(collection) };
    setSelected(fallback);
    try {
      const response = await api.getPublicCollection(collection.collectionId);
      const next = response.data;
      if (next?.skills?.length || next?.collection || next?.name) setSelected({ ...fallback, ...next, skills: next.skills?.length ? next.skills : fallback.skills });
    } catch (loadError) {
      if (!EXAMPLE_COLLECTIONS.some((item) => item.collectionId === collection.collectionId)) setError(formatConfigError(loadError));
    }
  };
  const submitCreate = async (event) => {
    event.preventDefault();
    if (!createName.trim()) { setCreateError("请填写合集名称"); return; }
    setCreating(true); setCreateError("");
    try {
      const response = await api.saveCollection(null, { collectionId: null, name: createName.trim(), description: createDescription.trim(), ownerTeamId: "platform", visibility: "public", skillIds: [], sortOrder: collections.length + 1 });
      const created = response.data ?? { collectionId: `local-${Date.now()}`, name: createName.trim(), description: createDescription.trim(), skillIds: [] };
      setCollections((items) => [created, ...items]); setCollectionTotal((total) => total + 1); setPage(1);
      setCreateOpen(false); setCreateName(""); setCreateDescription("");
      onToast?.("合集已创建");
    } catch (saveError) { setCreateError(formatConfigError(saveError)); }
    finally { setCreating(false); }
  };
  const exampleCount = useMemo(() => collections.filter((item) => EXAMPLE_COLLECTIONS.some((example) => example.collectionId === item.collectionId)).length, [collections]);
  const totalPages = Math.max(1, Math.ceil(collectionTotal / pageSize));
  const visibleCollections = collections;
  useDialogKeyboard(dialogRef, () => setCreateOpen(false), createOpen);
  useEffect(() => { if (page > totalPages) setPage(totalPages); }, [page, totalPages]);
  const pageNumbers = Array.from({ length: totalPages }, (_, index) => index + 1);
  if (loading) return <main className="content api-state">正在加载技能合集…</main>;
  return <main className="content placeholder-content collections-view">
    <div className="page-title-row"><div><h1>技能合集</h1><p>按业务场景组织可复用的 Skill，团队成员可直接查看和分发。</p></div></div>
    {error && <div className="api-state error-state"><span>{error}</span><button className="text-button" onClick={() => { setError(""); loadCollections(page); }}>重试</button></div>}
    <div className="filterbar collection-filterbar"><label className="filter-search"><Icon name="magnifying-glass" size={17} /><input value={query} onChange={(event) => { setQuery(event.target.value); setPage(1); }} placeholder="搜索合集名称 / 描述 / 团队" aria-label="搜索技能合集" /></label>{role === "admin" && <button className="collection-create-action primary-action" onClick={() => { setCreateError(""); setCreateOpen(true); }}><Icon name="plus" size={16} />创建合集</button>}<div className="filter-spacer" /><select value={sort} onChange={(event) => { setSort(event.target.value); setPage(1); }} aria-label="排序方式">{MARKET_SORT_OPTIONS.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}</select><button className={`view-toggle ${viewMode === "grid" ? "active" : ""}`} aria-label="网格视图" aria-pressed={viewMode === "grid"} onClick={() => setViewMode("grid")}><Icon name="squares-four" size={18} /></button><button className={`view-toggle ${viewMode === "list" ? "active" : ""}`} aria-label="列表视图" aria-pressed={viewMode === "list"} onClick={() => setViewMode("list")}><Icon name="list" size={18} /></button></div>
    <div className="result-caption collection-summary">共 {collectionTotal} 个合集 <span>{MARKET_SORT_OPTIONS.find((option) => option.value === sort)?.label}</span>{exampleCount ? <span> · 已提供 {exampleCount} 个示例合集</span> : null}</div>
    {!visibleCollections.length ? <div className="empty-state"><strong>没有找到匹配的合集</strong><span>试试更换关键词。</span></div> : viewMode === "grid" ? <div className="collection-grid skill-grid">{visibleCollections.map((item) => { const skillCount = getCollectionSkillCount(item); return <button className="skill-card collection-card" key={item.collectionId} onClick={() => open(item)}><div className="skill-card-head"><span className="skill-icon icon-purple"><span aria-hidden="true">✦</span></span><span className="skill-card-title"><strong className="collection-card-title">{item.name}</strong><small>合集</small></span><span className="badge badge-category">公开合集</span></div><p>{item.description || "暂无描述"}</p><div className="skill-tags"><span className="badge badge-purple">业务能力包</span><span className="badge badge-category">团队共享</span></div><div className="skill-owner"><span>{item.ownerTeamId || "平台运营"}</span></div><div className="skill-card-foot collection-card-foot"><span><Icon name="squares-four" size={14} /> {skillCount}<small>包含 Skill</small></span></div></button>; })}</div> : <div className="skill-list collection-list">{visibleCollections.map((item) => <CollectionListRow collection={item} key={item.collectionId} onOpen={open} />)}</div>}
    <div className="pagination collection-pagination" aria-label="合集分页"><button disabled={page <= 1} onClick={() => setPage((current) => current - 1)} aria-label="合集上一页"><Icon name="caret-left" size={15} /></button>{pageNumbers.map((pageNumber) => <button key={pageNumber} className={pageNumber === page ? "current" : ""} onClick={() => setPage(pageNumber)}>{pageNumber}</button>)}<button disabled={page >= totalPages} onClick={() => setPage((current) => current + 1)} aria-label="合集下一页"><Icon name="caret-right" size={15} /></button><span className="pagination-total">每页 {pageSize} 条</span></div>
    {selected && <section className="panel collection-detail"><div className="section-heading"><div><h2>{selected.collection?.name || selected.name}</h2><p>{selected.collection?.description || selected.description || ""}</p></div><button className="secondary-button" onClick={() => setSelected(null)}>关闭</button></div><div className="governance-list">{(selected.skills ?? []).map((skill) => <button className="governance-row" key={skill.id} onClick={() => onOpenSkill(skill.id)}><div><strong>{skill.name}</strong><span>{skill.version} · {skill.category}</span></div><span>{skill.description}</span></button>)}{!(selected.skills ?? []).length && <div className="empty-state">合集暂无可用 Skill 成员</div>}</div></section>}
    {createOpen && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setCreateOpen(false); }}><form ref={dialogRef} className="upload-modal collection-create-modal" onSubmit={submitCreate} role="dialog" aria-modal="true" aria-labelledby="create-collection-title" tabIndex={-1}><div className="modal-head"><div><h2 id="create-collection-title">创建技能合集</h2><p>创建后可在后台继续添加 Skill 成员。</p></div><button type="button" className="icon-button" onClick={() => setCreateOpen(false)} aria-label="关闭"><span>×</span></button></div><label className="distribution-field"><span>合集名称</span><input data-dialog-initial-focus value={createName} onChange={(event) => setCreateName(event.target.value)} placeholder="例如：网络运维助手合集" autoFocus /></label><label className="distribution-field"><span>合集描述</span><textarea value={createDescription} onChange={(event) => setCreateDescription(event.target.value)} placeholder="说明这个合集适合什么场景" rows="4" /></label>{createError && <div className="api-state error-state" role="alert">{createError}</div>}<div className="modal-actions"><button type="button" className="secondary-button" onClick={() => setCreateOpen(false)}>取消</button><button className="primary-action" disabled={creating}>{creating ? "创建中…" : "创建合集"}</button></div></form></div>}
  </main>;
}
