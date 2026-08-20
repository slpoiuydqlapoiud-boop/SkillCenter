import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const appSource = await readFile(new URL("../src/App.jsx", import.meta.url), "utf8");

test("login view uses one identity selector and conditionally renders admin fields", () => {
  assert.match(appSource, /aria-label="进入身份"/);
  assert.match(appSource, /value=\{loginMode\}/);
  assert.match(appSource, /requiresAdminCredentials\(loginMode\)/);
  assert.match(appSource, /管理员账号/);
  assert.match(appSource, /管理员密码/);
});
