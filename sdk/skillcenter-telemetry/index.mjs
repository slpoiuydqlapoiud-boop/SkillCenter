const SCHEMA_VERSION = "1.0";
const DEFAULT_QUEUE_KEY = "skillcenter.telemetry.v1.queue";
const DEFAULT_MAX_BATCH_SIZE = 100;
const DEFAULT_MAX_QUEUE_SIZE = 500;
const DEFAULT_MAX_ATTEMPTS = 3;
const DEFAULT_BASE_DELAY_MS = 1000;
const DEFAULT_MAX_DELAY_MS = 30_000;

const INVOCATION_FIELDS = new Set([
  "schemaVersion", "eventId", "occurredAt", "skillId", "version", "subject", "client",
  "sessionId", "status", "durationMs", "errorCode", "usage",
]);
const INSTALLATION_FIELDS = new Set([
  "schemaVersion", "eventId", "occurredAt", "skillId", "version", "fromVersion", "subject", "client",
  "deviceId", "action", "method", "outcome", "errorCode",
]);
const INVOCATION_STATUSES = new Set(["success", "failure", "cancelled", "timeout"]);
const INSTALLATION_ACTIONS = new Set(["install", "upgrade", "downgrade", "uninstall"]);
const INSTALLATION_METHODS = new Set(["one-click", "cli", "manual-zip"]);
const INVOCATION_CLIENT_TYPES = new Set(["codex", "department-agent", "skillmd-compatible", "gateway"]);
const INSTALLATION_CLIENT_TYPES = new Set(["codex", "department-agent", "skillmd-compatible"]);

export function createInvocationEvent(fields = {}) {
  return normalizeEvent("invocation", fields);
}

export function createInstallationEvent(fields = {}) {
  return normalizeEvent("installation", fields);
}

export function createTelemetryClient(options = {}) {
  const {
    baseUrl = "",
    endpoint = baseUrl,
    fetchImpl = globalThis.fetch,
    storage = createDefaultStorage(),
    queueKey = DEFAULT_QUEUE_KEY,
    maxBatchSize = DEFAULT_MAX_BATCH_SIZE,
    maxQueueSize = DEFAULT_MAX_QUEUE_SIZE,
    maxAttempts = DEFAULT_MAX_ATTEMPTS,
    baseDelayMs = DEFAULT_BASE_DELAY_MS,
    maxDelayMs = DEFAULT_MAX_DELAY_MS,
    sleep = (delayMs) => new Promise((resolve) => setTimeout(resolve, delayMs)),
    now = () => new Date(),
    randomUUID = () => globalThis.crypto?.randomUUID?.() ?? fallbackUuid(),
    headers = {},
    onError = () => {},
  } = options;

  if (typeof fetchImpl !== "function") throw new Error("fetch is required to create the telemetry client");
  if (!storage || typeof storage.getItem !== "function" || typeof storage.setItem !== "function") {
    throw new Error("storage must implement getItem and setItem");
  }
  if (!Number.isInteger(maxBatchSize) || maxBatchSize < 1 || maxBatchSize > 100) {
    throw new Error("maxBatchSize must be between 1 and 100");
  }
  if (!Number.isInteger(maxQueueSize) || maxQueueSize < maxBatchSize) {
    throw new Error("maxQueueSize must be at least maxBatchSize");
  }
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 8) {
    throw new Error("maxAttempts must be between 1 and 8");
  }

  let queue = loadQueue();
  let activeFlush = null;

  function enqueueInvocation(event) {
    return enqueue("invocation", event);
  }

  function enqueueInstallation(event) {
    return enqueue("installation", event);
  }

  function enqueue(kind, event) {
    const normalized = normalizeEvent(kind, event, { now, randomUUID });
    const key = `${kind}:${normalized.eventId}`;
    if (queue.some((item) => `${item.kind}:${item.event.eventId}` === key)) return normalized.eventId;
    if (queue.length >= maxQueueSize) throw new Error("telemetry queue is full");
    queue.push({ kind, event: normalized });
    persistQueue();
    return normalized.eventId;
  }

  function queueSize() {
    return queue.length;
  }

  function flush() {
    if (activeFlush) return activeFlush;
    activeFlush = flushQueue().finally(() => { activeFlush = null; });
    return activeFlush;
  }

  async function flushQueue() {
    const report = {
      invocations: emptyBatchReport(),
      installations: emptyBatchReport(),
      queued: queue.length,
    };
    for (const kind of ["invocation", "installation"]) {
      const items = queue.filter((item) => item.kind === kind).slice(0, maxBatchSize);
      if (items.length === 0) continue;
      const result = await sendBatch(kind, items.map((item) => item.event));
      const target = kind === "invocation" ? report.invocations : report.installations;
      Object.assign(target, result.report);
      if (result.ok) {
        const handled = new Set(result.handledEventIds);
        queue = queue.filter((item) => item.kind !== kind || !handled.has(item.event.eventId));
        persistQueue();
      }
    }
    report.queued = queue.length;
    return report;
  }

  async function sendBatch(kind, events) {
    const path = kind === "invocation"
      ? "/api/v1/events/invocations/batch"
      : "/api/v1/events/installations/batch";
    let lastFailure = { code: "TELEMETRY_SEND_FAILED", status: 0 };
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      try {
        const response = await fetchImpl(`${endpoint}${path}`, {
          method: "POST",
          headers: { Accept: "application/json", "Content-Type": "application/json", ...headers },
          body: JSON.stringify({ batchId: batchId(randomUUID), schemaVersion: SCHEMA_VERSION, events }),
        });
        if (response.ok) {
          const payload = await response.json().catch(() => ({}));
          const data = payload?.data;
          if (!data || !Array.isArray(data.results)) {
            lastFailure = { code: "TELEMETRY_INVALID_RESPONSE", status: response.status };
          } else {
            return { ok: true, handledEventIds: handledEventIds(data.results), report: batchReport(data) };
          }
        } else {
          lastFailure = { code: retryableStatus(response.status) ? "TELEMETRY_TRANSIENT_FAILURE" : "TELEMETRY_REJECTED", status: response.status };
          if (!retryableStatus(response.status)) break;
          const retryAfter = parseRetryAfter(response.headers);
          await waitBeforeRetry(attempt, retryAfter);
          continue;
        }
      } catch {
        lastFailure = { code: "TELEMETRY_TRANSIENT_FAILURE", status: 0 };
      }
      if (attempt < maxAttempts) await waitBeforeRetry(attempt, null);
    }
    onError({ kind, code: lastFailure.code, status: lastFailure.status, attempts: maxAttempts });
    return { ok: false, handledEventIds: [], report: { ...emptyBatchReport(), failed: true } };
  }

  async function waitBeforeRetry(attempt, retryAfterMs) {
    const exponential = Math.min(maxDelayMs, baseDelayMs * (2 ** (attempt - 1)));
    await sleep(Math.max(0, Math.min(maxDelayMs, retryAfterMs ?? exponential)));
  }

  function loadQueue() {
    try {
      const parsed = JSON.parse(storage.getItem(queueKey) ?? "[]");
      if (!Array.isArray(parsed)) return [];
      const recovered = [];
      for (const item of parsed) {
        if (!item || (item.kind !== "invocation" && item.kind !== "installation") || !item.event) continue;
        try {
          const event = item.kind === "invocation"
            ? createInvocationEvent(item.event)
            : createInstallationEvent(item.event);
          recovered.push({ kind: item.kind, event });
        } catch {
          // Never send an entry that does not satisfy the current event contract.
        }
      }
      return recovered.slice(-maxQueueSize);
    } catch {
      return [];
    }
  }

  function persistQueue() {
    storage.setItem(queueKey, JSON.stringify(queue));
  }

  return { enqueueInvocation, enqueueInstallation, flush, queueSize };
}

function normalizeEvent(kind, fields, { now = () => new Date(), randomUUID = () => globalThis.crypto?.randomUUID?.() ?? fallbackUuid() } = {}) {
  if (!fields || typeof fields !== "object" || Array.isArray(fields)) throw new Error("event must be an object");
  const allowed = kind === "invocation" ? INVOCATION_FIELDS : INSTALLATION_FIELDS;
  for (const field of Object.keys(fields)) {
    if (!allowed.has(field)) throw new Error(`unsupported event field: ${field}`);
  }
  const event = { ...fields };
  event.schemaVersion = event.schemaVersion ?? SCHEMA_VERSION;
  event.eventId = event.eventId ?? randomUUID();
  event.occurredAt = event.occurredAt ?? new Date(now()).toISOString();
  if (event.schemaVersion !== SCHEMA_VERSION) throw new Error("schemaVersion must be 1.0");
  assertUuid(event.eventId);
  assertDateTime(event.occurredAt);
  assertSlug(event.skillId, "skillId");
  assertSemver(event.version, "version");
  assertSubject(event.subject);
  assertClient(event.client, kind === "invocation" ? INVOCATION_CLIENT_TYPES : INSTALLATION_CLIENT_TYPES);
  if (kind === "invocation") normalizeInvocation(event);
  else normalizeInstallation(event);
  return event;
}

function normalizeInvocation(event) {
  assertString(event.sessionId, "sessionId", 16, 128, /^[A-Za-z0-9_-]+$/);
  if (!INVOCATION_STATUSES.has(event.status)) throw new Error("status is invalid");
  assertInteger(event.durationMs, "durationMs", 0, 86_400_000);
  if (["failure", "timeout"].includes(event.status)) {
    assertErrorCode(event.errorCode);
  } else if (event.errorCode !== undefined) {
    throw new Error("errorCode is not allowed for successful or cancelled events");
  }
  if (event.usage !== undefined) {
    if (!event.usage || typeof event.usage !== "object" || Array.isArray(event.usage)) throw new Error("usage is invalid");
    const keys = Object.keys(event.usage).sort();
    if (keys.join(",") !== "inputTokens,model,outputTokens") throw new Error("usage fields are invalid");
    assertString(event.usage.model, "usage.model", 1, 128);
    assertInteger(event.usage.inputTokens, "usage.inputTokens", 0, Number.MAX_SAFE_INTEGER);
    assertInteger(event.usage.outputTokens, "usage.outputTokens", 0, Number.MAX_SAFE_INTEGER);
  }
}

function normalizeInstallation(event) {
  assertString(event.deviceId, "deviceId", 16, 128, /^[A-Za-z0-9_-]+$/);
  if (!INSTALLATION_ACTIONS.has(event.action)) throw new Error("action is invalid");
  if (!INSTALLATION_METHODS.has(event.method)) throw new Error("method is invalid");
  if (!new Set(["success", "failure"]).has(event.outcome)) throw new Error("outcome is invalid");
  if (["upgrade", "downgrade"].includes(event.action)) assertSemver(event.fromVersion, "fromVersion");
  if (event.outcome === "failure") assertErrorCode(event.errorCode);
  else if (event.errorCode !== undefined) throw new Error("errorCode is not allowed for successful events");
}

function assertSubject(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)
      || Object.keys(value).sort().join(",") !== "teamId,userId") {
    throw new Error("subject is invalid");
  }
  assertString(value.userId, "subject.userId", 2, 128, /^[A-Za-z0-9._@-]+$/);
  assertSlug(value.teamId, "subject.teamId");
}

function assertClient(value, allowedTypes) {
  if (!value || typeof value !== "object" || Array.isArray(value)
      || Object.keys(value).sort().join(",") !== "type,version") {
    throw new Error("client is invalid");
  }
  if (!allowedTypes.has(value.type)) throw new Error("client.type is invalid");
  assertSemver(value.version, "client.version");
}

function assertErrorCode(value) {
  assertString(value, "errorCode", 3, 64, /^[A-Z][A-Z0-9_]+$/);
}

function assertSlug(value, field) {
  assertString(value, field, 2, 64, /^[a-z0-9]+(?:-[a-z0-9]+)*$/);
}

function assertSemver(value, field) {
  assertString(value, field, 5, 128, /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/);
}

function assertUuid(value) {
  assertString(value, "eventId", 36, 36, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
}

function assertDateTime(value) {
  assertString(value, "occurredAt", 20, 64);
  if (!/^\d{4}-\d{2}-\d{2}T.+(?:Z|[+-]\d{2}:\d{2})$/.test(value) || Number.isNaN(Date.parse(value))) {
    throw new Error("occurredAt is invalid");
  }
}

function assertInteger(value, field, min, max) {
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`${field} is invalid`);
}

function assertString(value, field, min, max, pattern) {
  if (typeof value !== "string" || value.length < min || value.length > max || (pattern && !pattern.test(value))) {
    throw new Error(`${field} is invalid`);
  }
}

function handledEventIds(results) {
  return results.filter((result) => result && typeof result.eventId === "string"
    && (result.accepted === true || result.duplicate === true || result.errorCode)).map((result) => result.eventId);
}

function batchReport(data) {
  return {
    accepted: numberOrZero(data.accepted),
    duplicates: numberOrZero(data.duplicates),
    rejected: numberOrZero(data.rejected),
    failed: false,
  };
}

function emptyBatchReport() {
  return { accepted: 0, duplicates: 0, rejected: 0, failed: false };
}

function numberOrZero(value) {
  return Number.isInteger(value) && value >= 0 ? value : 0;
}

function retryableStatus(status) {
  return status === 408 || status === 429 || status >= 500;
}

function parseRetryAfter(headers) {
  const value = headers?.get?.("Retry-After");
  if (value === null || value === undefined) return null;
  const seconds = Number.parseInt(value, 10);
  return Number.isFinite(seconds) ? Math.max(0, seconds * 1000) : null;
}

function batchId(randomUUID) {
  return `telemetry-${String(randomUUID()).replace(/[^A-Za-z0-9_-]/g, "").slice(0, 96)}`.slice(0, 128);
}

function createDefaultStorage() {
  if (globalThis.localStorage && typeof globalThis.localStorage.getItem === "function") return globalThis.localStorage;
  const values = new Map();
  return { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
}

function fallbackUuid() {
  const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
