import test from "node:test";
import assert from "node:assert/strict";

import {
  SKILLS,
  filterSkills,
  getNavigationForRole,
  getSkillById,
  formatMetric,
  formatMarketTitle,
  sortSkills,
  paginateItems,
  EXAMPLE_COLLECTIONS,
  getCollectionSkillCount,
  filterCollections,
  sortCollections,
  ROLES,
  NAV_ITEMS,
} from "../src/state.js";

test("market data has the three launch skills used by the source screen", () => {
  assert.ok(SKILLS.length >= 3);
  assert.deepEqual(
    SKILLS.slice(0, 3).map((skill) => skill.id),
    ["eox-query", "data-analyst", "ppt-generator"],
  );
});

test("search and category filters are deterministic and case insensitive", () => {
  assert.deepEqual(
    filterSkills(SKILLS, { query: "数据", category: "all" }).map((skill) => skill.id),
    ["data-analyst"],
  );
  assert.deepEqual(
    filterSkills(SKILLS, { query: "", category: "效率工具" }).map((skill) => skill.id),
    ["data-analyst", "ppt-generator"],
  );
  assert.equal(filterSkills(SKILLS, { query: "not-found", category: "all" }).length, 0);
});

test("navigation follows role permissions without exposing admin links to viewers", () => {
  assert.deepEqual(Object.keys(ROLES), ["developer", "admin"]);
  assert.deepEqual(getNavigationForRole("developer"), ["market", "my-skills", "upload"]);
  assert.deepEqual(getNavigationForRole("admin"), ["market", "my-skills", "upload", "review", "analytics", "exports", "operations", "settings"]);
});

test("publish entry uses the product wording for both permitted roles", () => {
  assert.equal(NAV_ITEMS.find((item) => item.id === "upload")?.label, "发布 Skill");
  assert.ok(getNavigationForRole("developer").includes("upload"));
  assert.ok(getNavigationForRole("admin").includes("upload"));
});

test("public developer role uses the canonical API role name", () => {
  assert.equal(ROLES.developer.actorRole, "developer");
  assert.equal(ROLES.admin.actorRole, "admin");
});

test("market sorting and pagination are deterministic", () => {
  const sorted = sortSkills(SKILLS, "updated");
  assert.equal(sorted[0].id, "eox-query");
  assert.equal(sortSkills(SKILLS, "downloads")[0].id, "knowledge-qa");
  assert.equal(sortSkills(SKILLS, "calls")[0].id, "knowledge-qa");
  assert.equal(sortSkills(SKILLS, "favorites")[0].id, "knowledge-qa");
  assert.deepEqual(paginateItems(SKILLS, 2, 4).map((skill) => skill.id), ["fault-locator", "test-case-generator", "code-generator", "topology-analysis"]);
});

test("two example collections are available before backend collections are configured", () => {
  assert.equal(EXAMPLE_COLLECTIONS.length, 2);
  assert.deepEqual(EXAMPLE_COLLECTIONS.map((item) => item.name), ["网络运维助手合集", "研发效能工具箱"]);
  assert.deepEqual(EXAMPLE_COLLECTIONS.map((item) => getCollectionSkillCount(item)), [4, 3]);
  assert.equal(getCollectionSkillCount({ skills: [{ id: "a" }, { id: "b" }] }), 2);
  assert.deepEqual(filterCollections(EXAMPLE_COLLECTIONS, { query: "网络" }).map((item) => item.collectionId), ["example-network"]);
  assert.deepEqual(sortCollections([{ collectionId: "a", name: "A", metrics: { downloads: 2 } }, { collectionId: "b", name: "B", metrics: { downloads: 9 } }], "downloads").map((item) => item.collectionId), ["b", "a"]);
});

test("skill lookup and metrics remain presentation-safe", () => {
  assert.equal(getSkillById("data-analyst").name, "数据分析助手");
  assert.equal(getSkillById("missing"), undefined);
  assert.equal(formatMetric(1248), "1,248");
  assert.equal(formatMetric(0), "0");
});

test("market title includes the current Skill total", () => {
  assert.equal(formatMarketTitle(13), "浏览 13 个 Skill");
  assert.equal(formatMarketTitle(0), "浏览 0 个 Skill");
});
