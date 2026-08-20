import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const styles = await readFile(new URL("../src/styles.css", import.meta.url), "utf8");

test("detail sidebar version history stays within the sidebar grid", () => {
  assert.match(
    styles,
    /\.detail-aside\s*\{[^}]*min-width:\s*0/,
    "detail sidebar must be allowed to shrink inside the parent grid",
  );
  assert.match(
    styles,
    /\.detail-aside\s+\.version-history\s+\.governance-row\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s+auto/,
    "version history rows must use a compact two-column layout in the narrow sidebar",
  );
});
