#!/usr/bin/env node

import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';

const DEFAULTS = Object.freeze({
  url: 'http://127.0.0.1:8081/api/v1/skills',
  durationSeconds: 10,
  concurrency: 4,
  requestTimeoutMs: 2_000,
  p95Ms: 1_000,
  minSuccessRate: 99,
  output: null,
});

export function parseArgs(argv = []) {
  const result = { ...DEFAULTS };
  for (const argument of argv) {
    const [name, ...valueParts] = argument.split('=');
    const value = valueParts.join('=');
    if (name === '--url') result.url = value;
    else if (name === '--duration-seconds') result.durationSeconds = numberOption(name, value);
    else if (name === '--concurrency') result.concurrency = numberOption(name, value);
    else if (name === '--request-timeout-ms') result.requestTimeoutMs = numberOption(name, value);
    else if (name === '--p95-ms') result.p95Ms = numberOption(name, value);
    else if (name === '--min-success-rate') result.minSuccessRate = numberOption(name, value);
    else if (name === '--output') result.output = value || null;
    else throw new Error(`unknown option: ${name}`);
  }

  validateUrl(result.url);
  bounded(result.durationSeconds, 1, 3_600, 'duration-seconds');
  boundedInteger(result.concurrency, 1, 100, 'concurrency');
  bounded(result.requestTimeoutMs, 50, 120_000, 'request-timeout-ms');
  bounded(result.p95Ms, 1, 600_000, 'p95-ms');
  bounded(result.minSuccessRate, 0, 100, 'min-success-rate');
  return result;
}

export async function collectSamples({
  url,
  durationMs,
  concurrency,
  requestTimeoutMs,
  fetchImpl = globalThis.fetch,
  now = () => performance.now(),
}) {
  if (typeof fetchImpl !== 'function') throw new Error('fetch is unavailable');
  bounded(durationMs, 1, 3_600_000, 'duration-ms');
  boundedInteger(concurrency, 1, 100, 'concurrency');
  bounded(requestTimeoutMs, 50, 120_000, 'request-timeout-ms');
  validateUrl(url);

  const samples = [];
  const deadline = now() + durationMs;
  let startedRequests = 0;

  const worker = async () => {
    while (startedRequests === 0 || now() < deadline) {
      if (startedRequests > 0 && now() >= deadline) break;
      startedRequests += 1;
      const startedAt = now();
      const controller = new AbortController();
      let timedOut = false;
      const timeout = setTimeout(() => {
        timedOut = true;
        controller.abort();
      }, requestTimeoutMs);
      try {
        const response = await fetchImpl(url, { method: 'GET', signal: controller.signal });
        const status = Number.isInteger(response?.status) ? response.status : 0;
        samples.push({
          durationMs: elapsedMs(now(), startedAt),
          status,
          outcome: status >= 200 && status < 400 ? 'success' : 'http_error',
        });
      } catch (error) {
        samples.push({
          durationMs: elapsedMs(now(), startedAt),
          status: 0,
          outcome: timedOut || error?.name === 'AbortError' ? 'timeout' : 'network_error',
        });
      } finally {
        clearTimeout(timeout);
      }
    }
  };

  await Promise.all(Array.from({ length: concurrency }, () => worker()));
  return samples.sort((left, right) => left.durationMs - right.durationMs);
}

export function summarizeSamples(samples = [], thresholds = {}) {
  const normalized = Array.isArray(samples) ? samples : [];
  const latencies = normalized
    .map((sample) => Number(sample?.durationMs))
    .filter(Number.isFinite)
    .map((value) => Math.max(0, Math.round(value)))
    .sort((left, right) => left - right);
  const statusCounts = {};
  for (const sample of normalized) {
    const status = Number.isInteger(sample?.status) ? sample.status : 0;
    statusCounts[String(status)] = (statusCounts[String(status)] ?? 0) + 1;
  }
  const orderedStatusCounts = Object.fromEntries(
    Object.entries(statusCounts).sort(([left], [right]) => Number(left) - Number(right)),
  );
  const successfulRequests = normalized.filter((sample) => sample?.outcome === 'success').length;
  const totalRequests = normalized.length;
  const successRate = totalRequests === 0
    ? 0
    : round(100 * successfulRequests / totalRequests, 2);
  const latencyMs = latencies.length === 0
    ? { min: 0, p50: 0, p95: 0, p99: 0, max: 0 }
    : {
        min: latencies[0],
        p50: percentile(latencies, 50),
        p95: percentile(latencies, 95),
        p99: percentile(latencies, 99),
        max: latencies[latencies.length - 1],
      };
  const p95Ms = finiteThreshold(thresholds.p95Ms, Infinity);
  const minSuccessRate = finiteThreshold(thresholds.minSuccessRate, 100);
  const passed = totalRequests > 0
    && latencyMs.p95 <= p95Ms
    && successRate >= minSuccessRate;

  return {
    totalRequests,
    successfulRequests,
    failedRequests: totalRequests - successfulRequests,
    successRate,
    statusCounts: orderedStatusCounts,
    latencyMs,
    thresholds: { p95Ms, minSuccessRate, passed },
  };
}

async function main(argv) {
  const options = parseArgs(argv);
  const samples = await collectSamples({
    url: options.url,
    durationMs: options.durationSeconds * 1_000,
    concurrency: options.concurrency,
    requestTimeoutMs: options.requestTimeoutMs,
  });
  const report = {
    generatedAt: new Date().toISOString(),
    targetUrl: options.url,
    durationSeconds: options.durationSeconds,
    concurrency: options.concurrency,
    requestTimeoutMs: options.requestTimeoutMs,
    ...summarizeSamples(samples, options),
  };
  const serialized = `${JSON.stringify(report, null, 2)}\n`;
  if (options.output) await writeFile(options.output, serialized, 'utf8');
  process.stdout.write(serialized);
  if (!report.thresholds.passed) process.exitCode = 2;
}

function numberOption(name, value) {
  if (value === '') throw new Error(`${name} requires a value`);
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`${name} must be a number`);
  return parsed;
}

function bounded(value, minimum, maximum, name) {
  if (!Number.isFinite(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be between ${minimum} and ${maximum}`);
  }
}

function boundedInteger(value, minimum, maximum, name) {
  bounded(value, minimum, maximum, name);
  if (!Number.isInteger(value)) throw new Error(`${name} must be an integer`);
}

function validateUrl(value) {
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error('url must be a valid HTTP(S) URL');
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error('url must use HTTP(S)');
  }
  if (parsed.username || parsed.password) {
    throw new Error('url must not contain credentials');
  }
}

function elapsedMs(current, startedAt) {
  return Math.max(0, Math.round(current - startedAt));
}

function percentile(values, requested) {
  const index = Math.max(0, Math.ceil((requested / 100) * values.length) - 1);
  return values[index];
}

function finiteThreshold(value, fallback) {
  return Number.isFinite(Number(value)) ? Number(value) : fallback;
}

function round(value, decimals) {
  const scale = 10 ** decimals;
  return Math.round(value * scale) / scale;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
