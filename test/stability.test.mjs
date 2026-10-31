import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { seed, connect, call, cli, project } from './client.mjs';

test('stability discovery returns paginated, cited candidates through MCP and the packaged CLI', { timeout: 60000 }, async () => {
  const temp = await mkdtemp(join(tmpdir(), 'testledger-stability-')); const db = join(temp, 'ledger.db');
  let client;
  try {
    await seed(db); const before = await readFile(db); client = await connect(db);
    const spec = (await client.listTools()).tools.find(t => t.name === 'test_stability');
    assert.equal(spec.annotations.readOnlyHint, true); assert.equal(spec.annotations.destructiveHint, false);
    assert.equal(spec.inputSchema.properties.window.maximum, 50); assert.equal(spec.inputSchema.properties.minTransitions.minimum, 2);
    const page = await call(client, spec.name, { runId: 'run-4', limit: 1 });
    assert.equal(page.totalTests, 9); assert.equal(page.totalCandidates, 2); assert.equal(page.shortHistoryTests, 3);
    assert.equal(page.intermittentTests, 1); assert.equal(page.retryRecoveryTests, 1); assert.equal(page.nextOffset, 1);
    const candidate = page.candidates[0];
    assert.equal(candidate.name, 'rejectsDuplicate'); assert.equal(candidate.transitions, 3); assert.equal(candidate.sameCommitTransitions, 0);
    assert.deepEqual(candidate.signals, ['INTERMITTENT_OUTCOMES']);
    assert.deepEqual(candidate.history.map(h => h.outcome), ['PASSED', 'FAILURE', 'PASSED', 'FAILURE']);
    for (const observation of candidate.history) {
      const detail = await call(client, 'test_evidence', { runId: observation.runId, testId: candidate.testId });
      assert.equal(detail.evidence, observation.evidence); assert.equal(detail.test.outcome, observation.outcome);
    }
    const second = await call(client, spec.name, { runId: 'run-4', limit: 1, offset: page.nextOffset, snapshot: page.snapshot });
    assert.equal(second.totalCandidates, 2); assert.equal(second.nextOffset, null);
    assert.equal(second.candidates[0].name, 'retriesDelivery'); assert.deepEqual(second.candidates[0].signals, ['PASSED_AFTER_RETRY']);
    assert.equal(second.candidates[0].missing, 3); assert.equal(second.candidates[0].history[0].evidence, null);
    assert.deepEqual(JSON.parse(await cli('stability', '--run', 'run-4', '--limit', '1', '--db', db)), page);
    const prompt = await client.getPrompt({ name: 'triage-tests', arguments: { project: 'shop', branch: 'main' } });
    assert.match(prompt.messages[0].content.text, /test_stability/); assert.match(prompt.messages[0].content.text, /untrusted/);
    assert.deepEqual(await readFile(db), before);
  } finally { await client?.close(); await rm(temp, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }); }
});

test('stability snapshots survive new heads, reject historical backfills, and validate MCP and CLI arguments', { timeout: 60000 }, async () => {
  const temp = await mkdtemp(join(tmpdir(), 'testledger-stability-pages-')); const db = join(temp, 'ledger.db');
  let client;
  try {
    await seed(db); client = await connect(db); const name = 'test_stability';
    const page = await call(client, name, { runId: 'run-4' });
    for (const invalid of [{ window: 2 }, { window: 51 }, { minTransitions: 1 }, { window: 3, minTransitions: 3 }, { limit: 21 }, { offset: -1 }, { window: '20' }, { snapshot: true }, { branch: 'other' }]) {
      assert.equal((await client.callTool({ name, arguments: { runId: 'run-4', ...invalid } })).isError, true, JSON.stringify(invalid));
    }
    await assert.rejects(cli('stability', '--run', 'run-4', '--window', '2', '--db', db), error => error.code === 1 && /window/.test(error.stderr));
    await cli('import', '--reports', resolve(project, 'examples/run-4'), '--db', db, '--id', 'future',
      '--project', 'shop', '--branch', 'main', '--commit', 'future', '--at', '2026-10-30T12:05:00Z');
    assert.equal((await call(client, name, { runId: 'run-4', snapshot: page.snapshot })).snapshot, page.snapshot);
    const reports = join(temp, 'empty'); await mkdir(reports); await writeFile(join(reports, 'TEST.xml'), '<testsuite/>');
    await cli('import', '--reports', reports, '--db', db, '--id', 'backfill',
      '--project', 'shop', '--branch', 'main', '--commit', 'late-import', '--at', '2026-10-30T12:02:30Z');
    const stale = await client.callTool({ name, arguments: { runId: 'run-4', snapshot: page.snapshot, offset: 1 } });
    assert.equal(stale.isError, true); assert.match(stale.content[0].text, /snapshot changed/);
    const refreshed = await call(client, name, { runId: 'run-4' });
    assert.notEqual(refreshed.snapshot, page.snapshot);
    assert.equal(refreshed.candidates.find(c => c.name === 'rejectsDuplicate').transitions, 2);
  } finally { await client?.close(); await rm(temp, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }); }
});
