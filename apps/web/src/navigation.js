const PUBLIC_VIEWS = new Set([
  "market",
  "collection",
  "analytics",
  "review",
  "installations",
  "favorites",
  "created",
  "invocations",
  "my-skills",
  "settings",
  "exports",
  "operations",
]);

export function hashForRoute({ view = "market", selectedId = null } = {}) {
  if (view === "detail" && selectedId) return `#/skills/${encodeURIComponent(selectedId)}`;
  return `#/${PUBLIC_VIEWS.has(view) ? view : "market"}`;
}

export function routeFromHash(hash = "") {
  const path = String(hash || "").replace(/^#/, "") || "/market";
  const detailMatch = path.match(/^\/skills\/([^/]+)$/);
  if (detailMatch) {
    return { view: "detail", selectedId: decodeURIComponent(detailMatch[1]) };
  }
  const view = path.replace(/^\//, "");
  return { view: PUBLIC_VIEWS.has(view) ? view : "market", selectedId: null };
}
