# Changelog

## 0.2.0 — 2026-10-30

- Add `performance_regressions`, a read-only MCP tool that compares passed test
  durations with a median from older runs in the same project and branch.
- Add `timings` for JSON analysis and `timing-check` for CI. The gate distinguishes
  regressions, insufficient samples, invalid input, and clear evaluated results.
- Exclude failed, skipped, retried, and unmeasured tests from timing comparisons.
  Return coverage counts and evidence for every baseline sample in a result.
- Detect baseline changes between pages with an optional snapshot guard.
- Include a five-run timing example, a CI usage guide, and tests through the
  packaged JVM CLI and an independent MCP client. Schema version 1 is unchanged.

## 0.1.0 — 2026-10-30

- Import JUnit XML into an immutable SQLite run history with project, branch,
  commit, and timestamp metadata.
- Compare failures, confirmed recoveries, new failures, missing cases, and skips.
- Group related stack signatures and inspect recent pass/fail transitions.
- Expose six read-only MCP tools and the triage-tests investigation prompt.
- Retain source report paths and XML lines with stable evidence identifiers.
- Add bounded XML parsing, external-entity rejection, retry hints, and credential
  pattern redaction before persistence.
- Include Windows and Unix distributions, a four-run demo, and protocol tests
  using an independent MCP client.
