import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { project, seed, seedTimings, connect, call, cli } from '../test/client.mjs';

const directory = resolve(project, 'build/demo'); await mkdir(directory, { recursive: true });
const db = resolve(directory, 'ledger.db');
await seed(db);
await seedTimings(db);
const client = await connect(db);
try {
  const comparison = await call(client, 'compare_runs', { baseId: 'run-3', headId: 'run-4' });
  const groups = await call(client, 'failure_groups', { runId: 'run-4' });
  const test = groups.groups.flatMap(g => g.examples).find(e => e.name === 'rejectsDuplicate');
  const history = await call(client, 'test_history', { project: 'shop', branch: 'main', testId: test.testId });
  const result = { transport: 'MCP stdio, Kotlin server / TypeScript client', comparison, groups, history };
  await writeFile(resolve(directory, 'evidence.json'), JSON.stringify(result, null, 2) + '\n');
  await writeFile(resolve(directory, 'report.md'), await cli('report', '--base', 'run-3', '--head', 'run-4', '--db', db));
  const timings = await call(client, 'performance_regressions', { runId: 'timing-5' });
  await writeFile(resolve(directory, 'timings.json'), JSON.stringify(timings, null, 2) + '\n');
  console.log(JSON.stringify({ changes: comparison.counts, failureGroups: groups.totalGroups, transitions: history.transitions,
    timingRegressions: timings.totalRegressions, timingCoverage: { evaluated: timings.evaluatedTests, insufficient: timings.insufficientSamples, ignored: timings.ignoredTests },
    report: 'build/demo/report.md', evidence: 'build/demo/evidence.json', timings: 'build/demo/timings.json' }, null, 2));
} finally { await client.close(); }
