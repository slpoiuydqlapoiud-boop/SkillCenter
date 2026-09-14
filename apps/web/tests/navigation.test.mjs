import assert from "node:assert/strict";
import test from "node:test";
import { hashForRoute, routeFromHash } from "../src/navigation.js";

test("encodes public views and skill details as browser routes", () => {
  assert.equal(hashForRoute({ view: "market" }), "#/market");
  assert.equal(hashForRoute({ view: "collection" }), "#/collection");
  assert.equal(hashForRoute({ view: "detail", selectedId: "eox query" }), "#/skills/eox%20query");
});

test("restores a view and selected skill from the URL hash", () => {
  assert.deepEqual(routeFromHash("#/market"), { view: "market", selectedId: null });
  assert.deepEqual(routeFromHash("#/skills/eox%20query"), { view: "detail", selectedId: "eox query" });
  assert.deepEqual(routeFromHash("#/unknown"), { view: "not-found", selectedId: null });
});

test("serializes the not-found page as a stable browser route", () => {
  assert.equal(hashForRoute({ view: "not-found" }), "#/404");
});
