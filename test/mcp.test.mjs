import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { seed, connect, call, cli } from './client.mjs';

test('Kotlin server interoperates with an independent MCP client and returns cited triage evidence', { timeout: 60000 }, async () => {
  const temp = await mkdtemp(join(tmpdir(), 'testledger-mcp-')); const db = join(temp, 'ledger.db');
  let client;
  try {
    await seed(db); client = await connect(db);
    const tools = (await client.listTools()).tools;
    assert.equal(tools.length, 6); assert.ok(tools.every(t => t.annotations.readOnlyHint));
    const runs = await call(client, 'list_runs', { project: 'shop', branch: 'main', limit: 2 });
    assert.deepEqual(runs.runs.map(r => r.run.id), ['run-4', 'run-3']); assert.equal(runs.nextOffset, 2);
    const diff = await call(client, 'compare_runs', { baseId: 'run-3', headId: 'run-4' });
    assert.deepEqual(diff.counts, { failed_after_skip: 1, new_failure: 1, not_observed: 1, now_skipped: 1, persistent_failure: 1, recovered: 1, regression: 2 });
    const groups = await call(client, 'failure_groups', { runId: 'run-4' });
    assert.equal(groups.totalGroups, 4); assert.equal(groups.groups[0].count, 2);
    const sample = groups.groups[0].examples.find(e => e.name === 'rejectsDuplicate'); assert.ok(sample);
    const history = await call(client, 'test_history', { project: 'shop', branch: 'main', testId: sample.testId });
    assert.equal(history.transitions, 3); assert.equal(history.intermittent, true);
    assert.deepEqual(history.history.map(h => h.outcome), ['PASSED', 'FAILURE', 'PASSED', 'FAILURE']);
    const database = groups.groups.flatMap(g => g.examples).find(e => e.name === 'opensPool');
    const evidence = await call(client, 'test_evidence', { runId: 'run-4', testId: database.testId });
    assert.match(evidence.test.detail, /REDACTED/); assert.equal(evidence.test.report, 'TEST-shop.xml');
    assert.ok(evidence.test.line > 0); assert.equal(evidence.evidence, database.evidence);
    assert.equal((await call(client, 'run_summary', { runId: 'run-4' })).retryHints, 1);
    const bad = await client.callTool({ name: 'compare_runs', arguments: { baseId: 'run-4', headId: 'run-1' } });
    assert.equal(bad.isError, true);
    assert.equal((await client.listPrompts()).prompts[0].name, 'triage-tests');
    const prompt = await client.getPrompt({ name: 'triage-tests', arguments: { project: 'shop', branch: 'main' } });
    assert.match(prompt.messages[0].content.text, /untrusted/);
    assert.match(prompt.messages[0].content.text, /not a recovery/);
    const report = await cli('report', '--base', 'run-3', '--head', 'run-4', '--db', db);
    assert.match(report, /2 passed, 5 failed, 1 skipped/); assert.match(report, /not_observed/);
    const bytes = await readFile(db); assert.equal(bytes.includes(Buffer.from('example-private-value')), false);
    assert.equal(bytes.includes(Buffer.from('example-do-not-store')), false);
  } finally {
    await client?.close(); await rm(temp, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test('CLI returns a nonzero exit for a missing ledger and invalid options', async () => {
  await assert.rejects(cli('summary', '--run', 'absent', '--db', join(tmpdir(), 'missing-testledger-' + Date.now(), 'ledger.db')), /Ledger not found/);
  await assert.rejects(cli('serve', '--dangerous', 'true'), /Unknown option/);
});

test('packaged JVM CLI preserves UTF-8 test names and report text on every platform', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'testledger-unicode-')); const db = join(temp, 'ledger.db');
  try {
    const reports = join(temp, 'reports'); await mkdir(reports);
    await writeFile(join(reports, 'TEST.xml'), '<testsuite name="shop"><testcase name="validação 日本語"><failure message="ação inválida">duração</failure></testcase></testsuite>');
    await cli('import', '--reports', reports, '--db', db, '--id', 'utf8', '--project', 'shop', '--branch', 'main', '--commit', 'abc', '--at', '2026-10-30T12:00:00Z');
    const groups = JSON.parse(await cli('failures', '--run', 'utf8', '--db', db));
    const item = groups.groups[0].examples[0]; assert.equal(item.name, 'validação 日本語');
    const evidence = JSON.parse(await cli('test', '--run', 'utf8', '--test', item.testId, '--db', db));
    assert.equal(evidence.message, 'ação inválida'); assert.equal(evidence.detail, 'duração');
  } finally { await rm(temp, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }); }
});
