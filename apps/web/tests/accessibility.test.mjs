import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { JSDOM } from "jsdom";
import { focusDialog, getFocusableElements, handleDialogKeydown } from "../src/accessibility.js";

const appSource = await readFile(new URL("../src/App.jsx", import.meta.url), "utf8");
const collectionsSource = await readFile(new URL("../src/CollectionsView.jsx", import.meta.url), "utf8");
const styles = await readFile(new URL("../src/styles.css", import.meta.url), "utf8");
const accessibilityModulePath = new URL("../src/accessibility.js", import.meta.url);
const accessibilitySource = existsSync(accessibilityModulePath) ? await readFile(accessibilityModulePath, "utf8") : "";

function relativeLuminance(hex) {
  const channels = [0, 2, 4].map((offset) => parseInt(hex.slice(1 + offset, 3 + offset), 16) / 255);
  return channels.map((channel) => channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
    .reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0);
}

function contrastRatio(foreground, background) {
  const foregroundLuminance = relativeLuminance(foreground);
  const backgroundLuminance = relativeLuminance(background);
  return (Math.max(foregroundLuminance, backgroundLuminance) + 0.05) / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05);
}

test("application shell provides a keyboard skip link to the stable main content target", () => {
  assert.match(appSource, /className="skip-link"/);
  assert.match(appSource, /href="#main-content"/);
  assert.match(appSource, /className="workspace" id="main-content" tabIndex=\{-1\}/);
});

test("primary navigation exposes a visible keyboard focus treatment", () => {
  assert.match(styles, /\.skip-link\s*\{/);
  assert.match(styles, /\.skip-link:focus/);
  assert.match(styles, /\.topnav-item:focus-visible/);
  assert.match(styles, /\.sidebar-item:focus-visible/);
});

test("interactive controls keep a shared focus-visible treatment and reduced-motion fallback", () => {
  assert.match(styles, /button:focus-visible/);
  assert.match(styles, /input:focus-visible/);
  assert.match(styles, /select:focus-visible/);
  assert.match(styles, /textarea:focus-visible/);
  assert.match(styles, /\.dropzone:focus-within/);
  assert.match(styles, /prefers-reduced-motion: reduce/);
});

test("critical status and control color pairs meet the WCAG contrast floor", () => {
  const pairs = [
    ["#ffffff", "#255ff0", 4.5],
    ["#34425f", "#ffffff", 4.5],
    ["#ffcf5a", "#0b2c84", 3],
    ["#b42318", "#fff1f0", 4.5],
  ];
  for (const [foreground, background, minimum] of pairs) {
    assert.equal(styles.includes(foreground), true, `${foreground} should remain in the product palette`);
    assert.equal(styles.includes(background), true, `${background} should remain in the product palette`);
    assert.ok(contrastRatio(foreground, background) >= minimum, `${foreground} on ${background} must meet ${minimum}:1`);
  }
});

test("modal keyboard behavior is centralized and traps focus", () => {
  assert.notEqual(accessibilitySource, "", "modal accessibility utility should exist");
  assert.match(accessibilitySource, /export function getFocusableElements/);
  assert.match(accessibilitySource, /export function handleDialogKeydown/);
  assert.match(accessibilitySource, /event\.key === "Escape"/);
  assert.match(accessibilitySource, /event\.key !== "Tab"/);
});

test("all application dialogs opt into the shared keyboard behavior", () => {
  assert.match(appSource, /useDialogKeyboard\(dialogRef, close\)/);
  assert.match(appSource, /useDialogKeyboard\(dialogRef, closeDialog, Boolean\(selected\)\)/);
  assert.match(appSource, /ref=\{dialogRef\}/);
  assert.match(collectionsSource, /useDialogKeyboard\(dialogRef/);
  assert.match(collectionsSource, /data-dialog-initial-focus/);
});

test("dialog focus utility selects the initial control and wraps Tab navigation", () => {
  const dom = new JSDOM("<section id='dialog' tabindex='-1'><button id='close'>关闭</button><input id='name' data-dialog-initial-focus><button id='submit'>提交</button></section>");
  const previousDocument = globalThis.document;
  globalThis.document = dom.window.document;
  try {
    const dialog = document.querySelector("#dialog");
    const controls = getFocusableElements(dialog);
    assert.deepEqual(controls.map((item) => item.id), ["close", "name", "submit"]);
    focusDialog(dialog);
    assert.equal(document.activeElement.id, "name");

    document.querySelector("#submit").focus();
    const forward = new dom.window.KeyboardEvent("keydown", { key: "Tab", cancelable: true });
    handleDialogKeydown(forward, dialog, () => {});
    assert.equal(forward.defaultPrevented, true);
    assert.equal(document.activeElement.id, "close");

    const closeCalls = [];
    const escape = new dom.window.KeyboardEvent("keydown", { key: "Escape", cancelable: true });
    handleDialogKeydown(escape, dialog, () => closeCalls.push("closed"));
    assert.deepEqual(closeCalls, ["closed"]);
    assert.equal(escape.defaultPrevented, true);
  } finally {
    if (previousDocument === undefined) delete globalThis.document;
    else globalThis.document = previousDocument;
    dom.window.close();
  }
});
