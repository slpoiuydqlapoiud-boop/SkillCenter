import test from "node:test";
import assert from "node:assert/strict";
import { createSkillApi } from "../src/api/skillApi.js";
import { formatConfigError, removeById, upsertById, validateGovernanceForm } from "../src/governance.js";

test("governance API client encodes admin and public collection routes", async () => {
  const calls = [];
  const api = createSkillApi((path, options = {}) => { calls.push({ path, options }); return Promise.resolve({ data: {} }); });
  await api.listTeams({ status: "active" });
  await api.saveTeam(null, { teamId: "team-a" });
  await api.saveRoleBinding("alice@example.com", { role: "maintainer", teamId: "team-a" });
  await api.addCollectionSkill("starter kit", "eox-query");
  await api.listPublicCollections({ page: 2, pageSize: 24 });
  assert.deepEqual(calls.map((call) => call.path), [
    "/api/v1/admin/teams?status=active",
    "/api/v1/admin/teams",
    "/api/v1/admin/role-bindings/alice%40example.com",
    "/api/v1/admin/collections/starter%20kit/skills/eox-query",
    "/api/v1/collections?page=2&pageSize=24",
  ]);
  assert.equal(calls[1].options.method, "POST");
  assert.equal(calls[3].options.method, "PUT");
});

test("governance helpers stay deterministic and explain conflicts", () => {
  assert.deepEqual(validateGovernanceForm({ name: "", sortOrder: -1 }, ["name"]), {
    name: "此字段必填", sortOrder: "排序必须是非负整数",
  });
  assert.deepEqual(upsertById([{ code: "a", name: "old" }], { code: "a", name: "new" }, "code"), [{ code: "a", name: "new" }]);
  assert.deepEqual(removeById([{ code: "a" }, { code: "b" }], "a", "code"), [{ code: "b" }]);
  assert.equal(formatConfigError({ code: "CONFIG_CONFLICT" }), "编码已存在或配置已被其他管理员更新");
});
