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
valid after further imports. There is no retention or delete command in 0.2.0.

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
JVM server, negotiates MCP through the TypeScript SDK, calls all seven tools, gets
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
