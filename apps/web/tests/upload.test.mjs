import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { uploadValidationError } from "../src/upload.js";

test("upload validation exposes every concrete package error", () => {
  assert.equal(
    uploadValidationError({
      valid: false,
      errors: ["缺少 SKILL.md", "skill.json 无法解析"],
    }),
    "缺少 SKILL.md；skill.json 无法解析",
  );
});

test("valid upload responses do not produce a validation error", () => {
  assert.equal(uploadValidationError({ valid: true, errors: [] }), "");
});

test("publish dialog describes skill.json as optional", async () => {
  const appSource = await readFile(new URL("../src/App.jsx", import.meta.url), "utf8");

  assert.match(appSource, /包含 SKILL\.md（skill\.json 可选）/);
  assert.doesNotMatch(appSource, /包含 SKILL\.md 与 skill\.json/);
});
