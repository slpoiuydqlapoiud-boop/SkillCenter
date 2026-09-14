import test from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { errorStateCopy, normalizeErrorState } from "../src/errorState.js";

const errorStatePath = new URL("../src/errorState.js", import.meta.url);
const errorStateSource = existsSync(errorStatePath) ? await readFile(errorStatePath, "utf8") : "";
const appSource = await readFile(new URL("../src/App.jsx", import.meta.url), "utf8");

test("normalizes API failures into safe user-facing system states", () => {
  assert.notEqual(errorStateSource, "", "error state normalization should exist");
  assert.match(errorStateSource, /export function normalizeErrorState/);
  assert.match(errorStateSource, /forbidden/);
  assert.match(errorStateSource, /not-found/);
  assert.match(errorStateSource, /server-error/);
  assert.match(errorStateSource, /maintenance/);
  assert.match(errorStateSource, /返回技能市场/);
});

test("maps HTTP and API error codes without exposing resource identity", () => {
  assert.equal(normalizeErrorState({ status: 403 }), "forbidden");
  assert.equal(normalizeErrorState({ code: "SKILL_NOT_FOUND", status: 404 }), "not-found");
  assert.equal(normalizeErrorState({ status: 503 }), "maintenance");
  assert.equal(normalizeErrorState({ code: "REQUEST_FAILED", status: 500 }), "server-error");
  assert.match(errorStateCopy("forbidden").description, /没有权限/);
  assert.doesNotMatch(errorStateCopy("not-found").description, /SKILL|skill-|id/i);
});

test("system error pages provide safe text and recovery actions", () => {
  assert.match(appSource, /function SystemStateView/);
  assert.match(appSource, /role="alert"/);
  assert.match(appSource, /重试/);
  assert.match(appSource, /normalizeErrorState/);
});
