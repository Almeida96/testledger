# Architecture

## Data flow

```text
JUnit XML → bounded StAX parser → redaction → immutable SQLite run
                                                ↓
                                    comparisons and history
                                                ↓
                                    MCP tools → agent client
```

The parser and database are independent of MCP. The CLI, Markdown exporter, and
MCP handlers use the same query layer. Only the import command opens a writable
connection. The MCP SDK handles protocol negotiation and stdio framing.

## Import semantics

The CLI validates metadata and parses all selected XML files before opening the
writable database. Insertion of the run and its observations happens in one
transaction. Any failure rolls back the transaction. A digest of sorted report
paths and content hashes detects repeated imports. A run ID identifies one
immutable import across the whole ledger, including its project and branch.

SQLite uses foreign keys, WAL, and a five-second busy timeout. A second process
can import a run while an MCP process reads existing runs. Query methods serialize
access to their JDBC connection. Existing runs never change, so evidence IDs stay
valid after further imports. There is no retention or delete command in 0.3.0.

Timestamps are parsed as instants and stored with a fixed nine-digit fractional
part in UTC. This makes SQL ordering consistent for whole seconds, fractions, and
timezone offsets. Equal timestamps use run ID as a deterministic tie-breaker for
listing; comparisons require a strictly older base.

Imports are not filesystem snapshots. Import a completed report export, not a
directory being rewritten by a test runner. A size change during reading is
rejected, but a same-size concurrent edit is not guaranteed to be detected.
Symlink checks are not a sandbox against another process modifying the directory.

## Test identity and groups

A test ID is the first 24 hexadecimal characters of SHA-256 over the relative
report path, suite path, class, and test name, separated by NUL characters.
Module paths prevent two modules with identical class/test names from colliding.
Names with parameters remain distinct. Moving a report or renaming a suite/test
creates a new identity; the comparison does not infer renames.

Failure fingerprints combine the failure type with the first non-framework JVM
stack frame, replacing the source line number. Without such a frame, they use
the message with numeric values and UUID-like strings normalized. This groups
similar symptoms and can merge unrelated failures. A fingerprint is never a
claim of shared root cause. Each group includes example observations for review.

## Timing queries

The timing query selects up to 50 strictly older runs in the head's project and
branch. It reads outcome, retry count, and duration from schema version 1; no
migration or derived timing table is needed. The XML parser represents an absent
duration as zero, so both zero and missing durations are excluded.

SQLite extracts the duration scalar from each eligible observation. The query
does not deserialize historical stack traces. Each eligible head test has a
bounded array with one duration slot per selected run; missing samples use zero.
The working set is proportional to head tests × window, plus one run's readings.
Names and complete evidence payloads are loaded only for the returned page.

Medians and threshold comparisons use decimal arithmetic. Both the absolute and
relative threshold are inclusive; the head must still be strictly slower. Results
are sorted by absolute increase, then test ID. Counts and gate status cover every
eligible head test, regardless of pagination.

A snapshot hash covers the head metadata, policy, and ordered baseline metadata.
Immutable runs make those IDs sufficient within one ledger. A caller may pass
that hash with the next offset; a backfill that changes the selected baseline
then fails explicitly. Ordinary newer imports do not affect an older head's
analysis. Selection happens once per query, so concurrent imports cannot mix two
baselines within a response. See [timing analysis](timing-analysis.md) for the
coverage and exit-code contract.

## Stability discovery

The stability query anchors its window to one imported head. It selects that
run plus up to 49 strictly older runs in the same project and branch, then scans
the union of their test IDs. This includes tests that disappeared from the head.
No table or schema version changes are required.

SQLite window functions compare adjacent observations per test. A transition
requires adjacent selected-run positions, strictly increasing timestamps, and
two non-skipped outcomes with different pass/fail states. Checking the run
positions prevents a missing observation from being mistaken for an adjacent
result. Failure and error belong to the same failed state.

Aggregation, ranking, and pagination happen inside SQLite. The JVM receives
summary counts and at most 20 candidate rows, then loads only those candidates'
outcomes, retry counts, names, and classes across the selected runs. It does not
load historical failure text or create a history object for every test. SQLite
still has to scan and sort the selected observations; query cost grows with the
number of tests and runs. The 50-run bound limits that scope.

The response includes chronological run metadata and a complete timeline for
each candidate. Missing observations have null outcomes and no citation. A
snapshot hash covers the run selection and policy. Passing it on later pages
rejects a changed window after a relevant backfill. Importing newer runs does
not shift an existing head's analysis.

The individual `test_history` query also excludes transitions between equal
timestamps. It continues to use the latest runs in a branch, while
`test_stability` ends at the explicitly selected head. See the
[stability guide](stability.md) for counts and ranking, and
[SQLite's window function reference](https://www.sqlite.org/windowfunctions.html)
for the underlying query operations.

## Agent interface

Tools validate argument names, types, identifiers, pagination, and query bounds.
Results include stable evidence IDs, not generated explanations. The MCP prompt
provides an investigation sequence and requires the agent to distinguish evidence
from hypotheses. It also explains that missing and skipped cases are not recoveries.

The server provides no model credentials, provider adapter, shell execution,
network ingestion, or automatic remediation. Its AI integration is the MCP tool
surface and reusable investigation prompt. A client may send returned evidence
to its chosen model. No model response was used to validate this release.

The independent protocol test seeds a real SQLite ledger, launches the packaged
JVM server, negotiates MCP through the TypeScript SDK, calls all eight tools, gets
the prompt, and checks the expected observations. This verifies interoperability
without assuming that a language model will always choose the right tools.

## Known limits

- JUnit XML is a family of formats. Only the documented elements are interpreted;
  suite counters and custom metadata are not a substitute for testcase results.
- Environment is not a separate dimension. Use consistent project/branch scopes
  for comparable environments. Do not mix operating systems or test matrices if
  their expected outcomes differ.
- `intermittent` means at least two adjacent pass/fail changes in up to 50 recent
  runs. It is not a probabilistic flakiness score.
- Stability discovery can raise the transition threshold, but it does not estimate
  failure probability or infer that an unchanged commit implies an unchanged
  environment. Retry signals depend on the runner recording retry elements.
- Timing thresholds compare recorded testcase durations. Runner contention,
  retries not represented in XML, and environment changes can explain increases.
  The median reduces sensitivity to outliers but is not a significance test.
- Parsing, comparison, and failure grouping run in memory within import limits.
  This release targets local project histories, not a multi-tenant analytics service.
- Redaction is a best-effort pattern filter. Unknown secrets and personal data can
  remain in messages or test names. There is no claim of complete anonymization.
- XML report line numbers identify testcase starts. Truncated evidence may require
  consulting the original report outside TestLedger.

Protocol implementation:
[official Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk).
