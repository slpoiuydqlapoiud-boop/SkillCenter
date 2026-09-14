import test from "node:test";
import assert from "node:assert/strict";

import { createDeveloperSession, LOGIN_MODES, requiresAdminCredentials } from "../src/auth.js";

test("developer can enter without credentials", () => {
  assert.deepEqual(createDeveloperSession(), {
    userId: "developer-user",
    role: "developer",
    displayName: "普通开发者",
  });
});

test("admin credentials are delegated to the API instead of being stored in the bundle", async () => {
  const source = await import("node:fs/promises").then((fs) => fs.readFile(new URL("../src/auth.js", import.meta.url), "utf8"));
  assert.doesNotMatch(source, /SkillCenter@2026/);
});

test("only the administrator entry mode requires credentials", () => {
  assert.equal(LOGIN_MODES.developer, "developer");
  assert.equal(LOGIN_MODES.admin, "admin");
  assert.equal(requiresAdminCredentials(LOGIN_MODES.developer), false);
  assert.equal(requiresAdminCredentials(LOGIN_MODES.admin), true);
});
