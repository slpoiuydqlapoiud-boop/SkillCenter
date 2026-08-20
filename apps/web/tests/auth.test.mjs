import test from "node:test";
import assert from "node:assert/strict";

import { authenticateAdmin, createDeveloperSession, LOGIN_MODES, requiresAdminCredentials } from "../src/auth.js";

test("developer can enter without credentials", () => {
  assert.deepEqual(createDeveloperSession(), {
    userId: "developer-user",
    role: "developer",
    displayName: "普通开发者",
  });
});

test("admin login requires the configured account and password", () => {
  assert.equal(authenticateAdmin("admin", "wrong"), null);
  assert.deepEqual(authenticateAdmin("admin", "SkillCenter@2026"), {
    userId: "platform-admin",
    role: "admin",
    displayName: "平台管理员",
  });
});

test("only the administrator entry mode requires credentials", () => {
  assert.equal(LOGIN_MODES.developer, "developer");
  assert.equal(LOGIN_MODES.admin, "admin");
  assert.equal(requiresAdminCredentials(LOGIN_MODES.developer), false);
  assert.equal(requiresAdminCredentials(LOGIN_MODES.admin), true);
});
