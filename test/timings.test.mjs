import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { seedTimings, connect, call, cli, project } from './client.mjs';

test('timing MCP tool returns a median baseline, coverage, and resolvable evidence without writing the ledger', { timeout: 60000 }, async () => {
  const temp = await mkdtemp(join(tmpdir(), 'testledger-timings-')); const db = join(temp, 'ledger.db');
  let client;
  try {
    await seedTimings(db);
    const before = await readFile(db);
    client = await connect(db);
    const tool = (await client.listTools()).tools.find(t => t.name === 'performance_regressions');
    assert.ok(tool.annotations.readOnlyHint);
    assert.equal(tool.inputSchema.properties.minIncreaseMs.minimum, 0);
    assert.equal(tool.inputSchema.properties.window.maximum, 50);
    const page = await call(client, tool.name, { runId: 'timing-5' });
    assert.equal(page.status, 'REGRESSIONS');
    assert.equal(page.totalTests, 5); assert.equal(page.eligibleTests, 3);
    assert.equal(page.evaluatedTests, 2); assert.equal(page.insufficientSamples, 1); assert.equal(page.ignoredTests, 2);
    const regression = page.regressions[0]; assert.equal(page.totalRegressions, 1);
    assert.equal(regression.name, 'searchesCatalog'); assert.equal(regression.medianSeconds, 1.15);
    assert.equal(regression.headSeconds, 2); assert.equal(regression.increaseSeconds, 0.85);
    for (const sample of regression.samples) {
      const observation = await call(client, 'test_evidence', { runId: sample.runId, testId: regression.testId });
      assert.equal(observation.evidence, sample.evidence); assert.equal(observation.test.seconds, sample.seconds);
    }
    assert.deepEqual(JSON.parse(await cli('timings', '--run', 'timing-5', '--db', db)), page);
    const beyond = await call(client, tool.name, { runId: 'timing-5', offset: 99, snapshot: page.snapshot });
    assert.equal(beyond.status, 'REGRESSIONS'); assert.equal(beyond.totalRegressions, 1); assert.deepEqual(beyond.regressions, []);
    const prompt = await client.getPrompt({ name: 'triage-tests', arguments: { project: 'catalog', branch: 'main' } });
    assert.match(prompt.messages[0].content.text, /performance_regressions/);
    assert.deepEqual(await readFile(db), before);
  } finally { await client?.close(); await rm(temp, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }); }
});

test('CI timing gate distinguishes regressions, clear results, insufficient samples, and invalid input', { timeout: 60000 }, async () => {
  const temp = await mkdtemp(join(tmpdir(), 'testledger-gate-')); const db = join(temp, 'ledger.db');
  async function expectExit(code, status, ...args) {
    await assert.rejects(cli('timing-check', ...args, '--db', db), error => {
      assert.equal(error.code, code);
      if (status) assert.equal(JSON.parse(error.stdout).status, status);
      else assert.match(error.stderr, /window/);
      return true;
    });
  }
  try {
    await seedTimings(db);
    await expectExit(2, 'REGRESSIONS', '--run', 'timing-5', '--offset', '99');
    await expectExit(3, 'INSUFFICIENT_DATA', '--run', 'timing-1');
    await expectExit(3, 'INSUFFICIENT_DATA', '--run', 'timing-5', '--min-increase-ms', '100000');
    await expectExit(1, null, '--run', 'timing-5', '--window', '2');
    const clear = JSON.parse(await cli('timing-check', '--run', 'timing-4', '--min-increase-ms', '100000', '--db', db));
    assert.equal(clear.status, 'CLEAR'); assert.equal(clear.evaluatedTests, 2);
    assert.equal((await cli('--version')).trim(), '0.3.0');
  } finally { await rm(temp, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }); }
});

test('MCP rejects invalid timing arguments and a backfill invalidates a page snapshot', { timeout: 60000 }, async () => {
  const temp = await mkdtemp(join(tmpdir(), 'testledger-snapshot-')); const db = join(temp, 'ledger.db');
  let client;
  try {
    await seedTimings(db); client = await connect(db);
    const name = 'performance_regressions';
    for (const invalid of [{ window: 2 }, { window: 51 }, { minSamples: 2 }, { minIncreaseMs: -1 }, { minIncreasePercent: '50' }, { limit: 21 }, { offset: 0.5 }, { snapshot: false }, { unexpected: true }]) {
      const result = await client.callTool({ name, arguments: { runId: 'timing-5', ...invalid } });
      assert.equal(result.isError, true, JSON.stringify(invalid));
    }
    const page = await call(client, name, { runId: 'timing-5' });
    await cli('import', '--reports', resolve(project, 'examples/timings/run-1'), '--db', db, '--id', 'backfill',
      '--project', 'catalog', '--branch', 'main', '--commit', 'late-import', '--at', '2026-10-30T13:03:30Z');
    const result = await client.callTool({ name, arguments: { runId: 'timing-5', snapshot: page.snapshot } });
    assert.equal(result.isError, true); assert.match(result.content[0].text, /snapshot changed/);
    assert.notEqual((await call(client, name, { runId: 'timing-5' })).snapshot, page.snapshot);
  } finally { await client?.close(); await rm(temp, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }); }
});
