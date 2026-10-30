import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { existsSync } from 'node:fs';

export const project = fileURLToPath(new URL('../', import.meta.url));
const distribution = process.env.TESTLEDGER_DIST || (existsSync(resolve(project, 'lib')) ? project : resolve(project, 'build/install/testledger'));
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const launch = ['-cp', join(distribution, 'lib', '*'), 'io.github.almeida96.testledger.MainKt'];
export async function cli(...args) {
  const { stdout } = await promisify(execFile)(java, [...launch, ...args], { timeout: 30000, maxBuffer: 2 * 1024 * 1024, windowsHide: true });
  return stdout;
}
export async function seed(db) {
  for (let n = 1; n <= 4; n++) await cli('import', '--reports', resolve(project, `examples/run-${n}`), '--db', db,
    '--id', `run-${n}`, '--project', 'shop', '--branch', 'main', '--commit', `sample-${n}`, '--at', `2026-10-30T12:0${n}:00Z`);
}
export async function connect(db) {
  const client = new Client({ name: 'testledger-interop', version: '0.1.0' });
  const transport = new StdioClientTransport({ command: java, args: [...launch, 'serve', '--db', db], stderr: 'pipe' });
  try { await client.connect(transport); return client; } catch (error) { await client.close(); throw error; }
}
export async function call(client, name, args) {
  const result = await client.callTool({ name, arguments: args });
  if (result.isError) throw new Error(JSON.stringify(result.content));
  return result.structuredContent;
}
