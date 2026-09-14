import test from 'node:test';
import assert from 'node:assert/strict';

import { collectSamples, parseArgs, summarizeSamples } from './slo-smoke.mjs';

test('summarizeSamples reports latency percentiles, status counts and threshold result', () => {
  const report = summarizeSamples([
    { durationMs: 10, status: 200, outcome: 'success' },
    { durationMs: 20, status: 200, outcome: 'success' },
    { durationMs: 30, status: 200, outcome: 'success' },
    { durationMs: 80, status: 503, outcome: 'http_error' },
  ], { p95Ms: 80, minSuccessRate: 75 });

  assert.deepEqual(report.statusCounts, { '200': 3, '503': 1 });
  assert.equal(report.totalRequests, 4);
  assert.equal(report.successfulRequests, 3);
  assert.equal(report.failedRequests, 1);
  assert.equal(report.successRate, 75);
  assert.deepEqual(report.latencyMs, { min: 10, p50: 20, p95: 80, p99: 80, max: 80 });
  assert.equal(report.thresholds.passed, true);
});

test('parseArgs applies bounded defaults and rejects unsafe load parameters', () => {
  const parsed = parseArgs([
    '--url=https://example.internal/health',
    '--duration-seconds=12',
    '--concurrency=6',
    '--request-timeout-ms=1500',
    '--p95-ms=250',
    '--min-success-rate=99.5',
  ]);

  assert.deepEqual(parsed, {
    url: 'https://example.internal/health',
    durationSeconds: 12,
    concurrency: 6,
    requestTimeoutMs: 1500,
    p95Ms: 250,
    minSuccessRate: 99.5,
    output: null,
  });
  assert.throws(() => parseArgs(['--concurrency=0']), /concurrency/);
  assert.throws(() => parseArgs(['--concurrency=1.5']), /concurrency/);
  assert.throws(() => parseArgs(['--url=http://user:password@example.internal']), /credentials/);
});

test('collectSamples uses bounded GET requests without reading response bodies', async () => {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    return {
      status: 204,
      text() {
        throw new Error('response body must not be read');
      },
    };
  };

  const samples = await collectSamples({
    url: 'https://example.internal/health',
    durationMs: 1,
    concurrency: 1,
    requestTimeoutMs: 100,
    fetchImpl,
  });

  assert.ok(samples.length >= 1);
  assert.equal(samples.length, calls.length);
  assert.ok(samples.every((sample) => sample.outcome === 'success'));
  assert.ok(calls.every(({ options }) => options.method === 'GET' && options.body === undefined));
});
