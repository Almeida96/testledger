# TestLedger

[![CI](https://github.com/Almeida96/testledger/actions/workflows/ci.yml/badge.svg)](https://github.com/Almeida96/testledger/actions/workflows/ci.yml)

Test-run history and failure evidence for AI agents, written in Kotlin.

TestLedger imports JUnit XML into a local SQLite database and exposes six read-only
MCP tools. An agent can compare builds, inspect grouped failures, check a test's
recent history, and cite the exact observation behind a conclusion.

It handles a common triage mistake: a failing test that disappears from the next
run is **not** marked as recovered. Skipped tests, new failures, regressions, and
confirmed passes have separate categories.

## What it does

- Imports reports from Gradle, Maven, and other runners that produce JUnit XML.
- Stores immutable runs with project, branch, commit, and timestamp metadata.
- Groups failures by exception type and stack location, with evidence for each group.
- Compares runs and tracks adjacent pass/fail transitions within one branch.
- Exposes MCP tools and a `triage-tests` prompt for an agent's investigation.
- Produces a Markdown comparison report for a pull request or incident record.

There is no hosted model or API key inside TestLedger. Your MCP client supplies
the agent and decides which tool results to send to its model provider. The
included demo exercises the real protocol without making a model call.

## Install

Requires **Java 21 or later**. Download and extract
[testledger-0.1.0.zip](https://github.com/Almeida96/testledger/releases/download/v0.1.0/testledger-0.1.0.zip).
The archive includes dependencies and launchers for Windows and Unix systems.

```sh
./testledger-0.1.0/bin/testledger --help
```

PowerShell:

```powershell
.\testledger-0.1.0\bin\testledger.bat --help
```

Set `JAVA_HOME` to your JDK directory, or make `java` available on `PATH`.
The following examples assume `testledger` is on your path; on Windows use the
`.bat` launcher. No global Gradle installation is needed to build from source.

## Import a run

```sh
testledger import \
  --reports ./build/test-results/test \
  --id build-142 \
  --project checkout \
  --branch main \
  --commit 9a13be2 \
  --at 2026-10-30T12:00:00Z
```

For Maven, point `--reports` at `target/surefire-reports`. For multiple modules,
export their report directories under one root while preserving module paths.
Use the same root layout on later imports so test identities remain stable.
Reports are read recursively; build commands are never executed by TestLedger.

The database defaults to `.testledger/ledger.db` in the current directory.
Every command accepts `--db PATH`. Keep the database outside your report directory.
Importing the same run ID, reports, and metadata is idempotent. Reusing an ID with
different data fails; earlier evidence cannot be silently overwritten.

```sh
testledger runs --project checkout --branch main
testledger summary --run build-142
testledger failures --run build-142
testledger compare --base build-141 --head build-142
testledger history --project checkout --branch main --test TEST_ID --window 20
testledger test --run build-142 --test TEST_ID
testledger report --base build-141 --head build-142 > triage.md
```

Commands return JSON except `report`, which returns Markdown. Invalid input and
missing data produce a nonzero exit code. `runs`, `failures`, and `compare` support
`--offset` and `--limit`; JSON responses include `nextOffset` when another page exists.

## Connect your agent

Import reports first, then add a stdio MCP server to your client's configuration.
Use absolute paths because clients often start tools from a different directory.

```json
{
  "mcpServers": {
    "testledger": {
      "command": "java",
      "args": [
        "-cp",
        "/absolute/path/testledger-0.1.0/lib/*",
        "io.github.almeida96.testledger.MainKt",
        "serve",
        "--db",
        "/absolute/path/checkout/.testledger/ledger.db"
      ]
    }
  }
}
```

On Windows, use forward slashes in JSON paths, such as
`C:/tools/testledger-0.1.0/lib/*`. The Java launcher expands the classpath wildcard;
no shell wrapper is needed. Client configuration locations vary.

| Tool | What the agent receives |
| --- | --- |
| `list_runs` | Run metadata and final outcome counts for one project and branch |
| `run_summary` | Passed, failed, skipped, and retry-hint counts |
| `failure_groups` | Shared failure signatures with up to three example evidence IDs |
| `compare_runs` | Paginated changes between two chronological runs |
| `test_history` | Recent observations, missing runs, and pass/fail transitions |
| `test_evidence` | One testcase's failure text, source report, XML line, and retry hints |

Select the **`triage-tests`** MCP prompt with a project and branch, or ask your agent:

> Compare the latest two checkout runs on main. Inspect the new failures, check
> whether they appeared in earlier runs, and cite the evidence. Separate facts
> from hypotheses and list the next checks you would make.

Evidence IDs have the form `testledger://runs/RUN_ID/tests/TEST_ID`. They are
stable references resolved through `test_evidence` using those two IDs, not HTTP
links or advertised MCP resources. The tool also returns a relative XML report
path and the testcase's starting line. The database retains the observation even
if the original report is later removed.

The server opens SQLite read-only. It cannot import reports, run a shell, change
code, or start a build. The owner refreshes the ledger with the CLI. This release
supports stdio only; it does not expose a public HTTP endpoint.

## Comparison rules

| Category | Base → head |
| --- | --- |
| `regression` | Passed → failed/error |
| `persistent_failure` | Failed/error → failed/error |
| `new_failure` | Not present → failed/error |
| `failed_after_skip` | Skipped → failed/error |
| `recovered` | Failed/error → passed |
| `now_skipped` | Failed/error → skipped |
| `not_observed` | Failed/error → not present |

Unchanged successful tests do not appear in the comparison. Runs must belong to
the same project and branch, and the base timestamp must precede the head.
These rules compare observations; they do not establish that a commit caused a
failure. Keep separate project or branch labels for incompatible test environments.

`test_history` marks a sequence as intermittent after at least two adjacent
pass/fail transitions in the selected window. Missing and skipped observations
break the sequence. Different commits, dependencies, or environments may explain
the changes, so this signal is **not proof of a flaky test**.

## Reproduce the example

```sh
git clone https://github.com/Almeida96/testledger.git
cd testledger
./gradlew test installDist
npm install --global pnpm@11.25.0
pnpm install --frozen-lockfile
pnpm test:mcp
pnpm demo
```

Use `gradlew.bat` on Windows. JavaScript is used only for the independent MCP
client and protocol tests; the application, parser, persistence, CLI, and server
are Kotlin. These optional client scripts require Node 24 or later.

The demo imports four fictional shopping-service test runs and connects a
TypeScript MCP client to the Kotlin server. It finds two regressions, four failure
groups, and three outcome transitions for one test. It also keeps a missing test
and a skipped test separate from the recovered test.

Results are saved to `build/demo/report.md` and `build/demo/evidence.json`.
See the checked-in [sample report](docs/sample-report.md),
[sample evidence](docs/sample-evidence.json), and [demo walkthrough](docs/demo.md).
These fixtures test known scenarios; they are not a benchmark of an LLM's diagnosis.

## Input and privacy boundaries

The parser accepts UTF-8 JUnit-style XML with `testsuite` or `testsuites` roots.
Nested suites, failures, errors, skipped cases, CDATA, and Surefire retry elements
are supported. Suite-level aggregate counters are ignored; counts come from
individual testcases. `flakyFailure` and `flakyError` entries record prior failed
attempts without changing a successful final outcome. `rerunFailure` and
`rerunError` preserve the final failure already present in the report.

Test identity combines the relative report path, suite path, class name, and test
name. Renames and report moves create new identities. Duplicate identities in
one import are rejected, so parameterized tests need distinct exported names.

DTD and external entities are rejected. Symlinks and hidden report paths are
skipped. Imports are bounded to 1,000 XML files, 2 MiB per file, 25 MiB total, and
25,000 testcases. Failure details are capped at 12,000 characters and carry a
truncation flag. Names are limited to 512 characters and suite paths to 1,024.

Suite properties, `system-out`, and `system-err` are not retained. Credential-like
assignments, bearer tokens, URL credentials, and selected token formats are
redacted before evidence is stored. This is pattern-based filtering, not a secret
scanner guarantee. Review reports before sharing them with any agent or provider.
The ledger contains test names and failure text; keep it out of source control.

Report contents are untrusted. The MCP prompt tells the agent to ignore embedded
instructions, but client/model behavior remains outside the server's control.

## Development

```sh
./gradlew test installDist distZip
pnpm test:mcp
pnpm demo
```

JUnit tests cover parser limits, external-entity rejection, credential redaction,
retries, immutable imports, branch isolation, pagination, comparisons, and history.
Protocol tests launch the installed JVM distribution and use the official
TypeScript MCP client. They also check UTF-8 on Windows and nonzero CLI exits.
CI runs Java 21 on Linux and Windows. JaCoCo reports are available under
`build/reports/jacoco/test`.

Dependencies are pinned and Gradle dependency locks are committed. The Gradle
wrapper distribution has a SHA-256 checksum. See [architecture](docs/architecture.md)
for storage, grouping, and consistency details.

MIT license.
