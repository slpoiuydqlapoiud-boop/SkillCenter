import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import viteConfig from "../vite.config.mjs";

const indexHtml = await readFile(new URL("../index.html", import.meta.url), "utf8");
const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
const readme = await readFile(new URL("../README.md", import.meta.url), "utf8");

test("development server listens on every network interface for LAN access", () => {
  assert.equal(viteConfig.server.host, "0.0.0.0");
  assert.match(packageJson.scripts.dev, /--host 0\.0\.0\.0/);
  assert.doesNotMatch(readme, /run dev -- --host 127\.0\.0\.1/);
});

test("brands the browser tab and declares a SkillCenter favicon", () => {
  assert.match(indexHtml, /<title>SkillCenter<\/title>/);
  assert.match(indexHtml, /rel="icon"[^>]+href="\/favicon\.svg"/);
});

test("returns a stable 503 JSON response when the API proxy cannot connect", () => {
  const proxyConfig = viteConfig.server.proxy["/api"];
  assert.equal(typeof proxyConfig, "object");
  assert.equal(proxyConfig.target, "http://127.0.0.1:8080");

  let errorHandler;
  proxyConfig.configure({
    on(event, handler) {
      assert.equal(event, "error");
      errorHandler = handler;
    },
  });

  const response = {
    headersSent: false,
    writeHead(status, headers) {
      this.status = status;
      this.headers = headers;
    },
    end(body) {
      this.body = body;
    },
  };
  errorHandler(new Error("connect ECONNREFUSED"), {}, response);

  assert.equal(response.status, 503);
  assert.equal(response.headers["Content-Type"], "application/json; charset=utf-8");
  assert.deepEqual(JSON.parse(response.body), {
    error: {
      code: "API_UNAVAILABLE",
      message: "Skill Center API is temporarily unavailable",
      details: [],
    },
    requestId: "proxy",
  });
});
