import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  createInstallationEvent,
  createInvocationEvent,
  createTelemetryClient,
} from "../index.mjs";

const packageRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

class MemoryStorage {
  #values = new Map();

  getItem(key) {
    return this.#values.get(key) ?? null;
  }

  setItem(key, value) {
    this.#values.set(key, value);
  }
}

function jsonResponse(data, status = 202, headers = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name) => headers[name] ?? null },
    json: async () => ({ data }),
  };
}

function invocationEvent(eventId = "00000000-0000-4000-8000-000000000001") {
  return createInvocationEvent({
    eventId,
    occurredAt: "2026-09-08T08:00:00Z",
    skillId: "summarize-release-notes",
    version: "1.0.0",
    subject: { userId: "alice", teamId: "platform" },
    client: { type: "codex", version: "1.2.3" },
    sessionId: "session-1234567890",
    status: "success",
    durationMs: 42,
  });
}

function installationEvent(eventId = "00000000-0000-4000-8000-000000000002") {
  return createInstallationEvent({
    eventId,
    occurredAt: "2026-09-08T08:00:00Z",
    skillId: "summarize-release-notes",
    version: "1.0.0",
    subject: { userId: "alice", teamId: "platform" },
    client: { type: "codex", version: "1.2.3" },
    deviceId: "device-1234567890",
    action: "install",
    method: "cli",
    outcome: "success",
  });
}

test("flushes separate invocation and installation batches and removes accepted receipts", async () => {
  const requests = [];
  const client = createTelemetryClient({
    baseUrl: "https://skill-center.example",
    storage: new MemoryStorage(),
    fetchImpl: async (url, options) => {
      requests.push({ url, body: JSON.parse(options.body) });
      if (url.endsWith("/invocations/batch")) {
        return jsonResponse({
          batchId: requests.at(-1).body.batchId,
          accepted: 1,
          duplicates: 1,
          rejected: 0,
          results: [
            { eventId: requests.at(-1).body.events[0].eventId, accepted: true, duplicate: false, errorCode: null },
            { eventId: requests.at(-1).body.events[1].eventId, accepted: false, duplicate: true, errorCode: null },
          ],
        });
      }
      return jsonResponse({
        batchId: requests.at(-1).body.batchId,
        accepted: 1,
        duplicates: 0,
        rejected: 0,
        results: [{ eventId: requests.at(-1).body.events[0].eventId, accepted: true, duplicate: false, errorCode: null }],
      });
    },
  });

  client.enqueueInvocation(invocationEvent());
  client.enqueueInvocation(invocationEvent("00000000-0000-4000-8000-000000000003"));
  client.enqueueInstallation(installationEvent());

  const result = await client.flush();

  assert.equal(result.invocations.accepted, 1);
  assert.equal(result.invocations.duplicates, 1);
  assert.equal(result.installations.accepted, 1);
  assert.equal(result.queued, 0);
  assert.equal(requests.length, 2);
  assert.deepEqual(Object.keys(requests[0].body).sort(), ["batchId", "events", "schemaVersion"]);
  assert.equal(requests[0].body.schemaVersion, "1.0");
  assert.equal("prompt" in requests[0].body.events[0], false);
});

test("retries transient transport failures with bounded exponential backoff and persists the queue", async () => {
  const storage = new MemoryStorage();
  const delays = [];
  let attempts = 0;
  const first = createTelemetryClient({
    storage,
    fetchImpl: async () => {
      attempts += 1;
      throw new Error("network down");
    },
    maxAttempts: 2,
    baseDelayMs: 25,
    sleep: async (delay) => delays.push(delay),
  });

  first.enqueueInvocation(invocationEvent());
  const failed = await first.flush();

  assert.equal(attempts, 2);
  assert.deepEqual(delays, [25]);
  assert.equal(failed.queued, 1);

  const second = createTelemetryClient({
    storage,
    fetchImpl: async (_url, options) => {
      const body = JSON.parse(options.body);
      return jsonResponse({
        batchId: body.batchId,
        accepted: 1,
        duplicates: 0,
        rejected: 0,
        results: [{ eventId: body.events[0].eventId, accepted: true, duplicate: false, errorCode: null }],
      });
    },
  });

  assert.equal(second.queueSize(), 1);
  const recovered = await second.flush();
  assert.equal(recovered.queued, 0);
});

test("honors Retry-After without allowing an unbounded delay", async () => {
  const delays = [];
  let attempts = 0;
  const client = createTelemetryClient({
    storage: new MemoryStorage(),
    maxAttempts: 2,
    baseDelayMs: 10,
    maxDelayMs: 100,
    sleep: async (delay) => delays.push(delay),
    fetchImpl: async (_url, options) => {
      attempts += 1;
      if (attempts === 1) return jsonResponse({}, 429, { "Retry-After": "3600" });
      const body = JSON.parse(options.body);
      return jsonResponse({
        batchId: body.batchId,
        accepted: 1,
        duplicates: 0,
        rejected: 0,
        results: [{ eventId: body.events[0].eventId, accepted: true, duplicate: false, errorCode: null }],
      });
    },
  });

  client.enqueueInvocation(invocationEvent());
  const result = await client.flush();

  assert.equal(result.queued, 0);
  assert.deepEqual(delays, [100]);
});

test("rejects sensitive or unknown event fields before they enter the durable queue", () => {
  const client = createTelemetryClient({ storage: new MemoryStorage() });

  assert.throws(() => client.enqueueInvocation({
    ...invocationEvent(),
    prompt: "must never be persisted",
  }), /unsupported event field: prompt/);
  assert.throws(() => client.enqueueInvocation({
    ...invocationEvent(),
    usage: { model: "gpt", inputTokens: 1, outputTokens: 2 },
    secret: "must never be persisted",
  }), /unsupported event field: secret/);
  assert.equal(client.queueSize(), 0);
});

test("drops tampered or legacy queue entries during startup recovery", () => {
  const storage = new MemoryStorage();
  storage.setItem("skillcenter.telemetry.v1.queue", JSON.stringify([
    { kind: "invocation", event: { ...invocationEvent(), prompt: "must be dropped" } },
    { kind: "installation", event: installationEvent() },
  ]));

  const client = createTelemetryClient({ storage });

  assert.equal(client.queueSize(), 1);
});

test("publishes a typed consumer contract without shipping test fixtures", () => {
  const packageJson = JSON.parse(fs.readFileSync(path.join(packageRoot, "package.json"), "utf8"));
  const declarationPath = path.join(packageRoot, packageJson.types);

  assert.equal(packageJson.types, "./index.d.ts");
  assert.deepEqual(packageJson.files, ["index.mjs", "index.d.ts", "README.md"]);
  assert.equal(fs.existsSync(declarationPath), true);

  const declaration = fs.readFileSync(declarationPath, "utf8");
  assert.match(declaration, /export declare function createInvocationEvent/);
  assert.match(declaration, /export declare function createInstallationEvent/);
  assert.match(declaration, /export declare function createTelemetryClient/);
});

test("uses injected clock and UUID factories for newly created client events", () => {
  const storage = new MemoryStorage();
  const client = createTelemetryClient({
    storage,
    now: () => new Date("2026-09-08T08:00:00Z"),
    randomUUID: () => "00000000-0000-4000-8000-000000000099",
  });
  const { eventId: _eventId, occurredAt: _occurredAt, ...fields } = invocationEvent();

  assert.equal(client.enqueueInvocation(fields), "00000000-0000-4000-8000-000000000099");
  const queued = JSON.parse(storage.getItem("skillcenter.telemetry.v1.queue"));
  assert.equal(queued[0].event.eventId, "00000000-0000-4000-8000-000000000099");
  assert.equal(queued[0].event.occurredAt, "2026-09-08T08:00:00.000Z");
});

test("enforces the schema client-type boundary for installation events", () => {
  assert.doesNotThrow(() => createInvocationEvent({
    ...invocationEvent(),
    client: { type: "gateway", version: "1.2.3" },
  }));
  assert.throws(() => createInstallationEvent({
    ...installationEvent(),
    client: { type: "gateway", version: "1.2.3" },
  }), /client.type is invalid/);
});
