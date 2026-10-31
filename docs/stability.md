# Discovering intermittent outcomes

`test_stability` finds tests worth investigating without requiring their IDs in
advance. It returns two kinds of signal: repeated changes between final pass and
fail outcomes, and final passes that contain reported retry failures. The same
query is available through the CLI:

```sh
testledger stability --run build-142 --window 20 --min-transitions 2
```

The tool does not label tests as proven flaky, quarantine them, or change build
results. Code changes, test data, dependencies, and runner conditions can all
explain a change in outcome.

## Run selection

The window contains the selected head and up to `window - 1` strictly older runs
in its project and branch. The head counts toward this limit. Runs from other
projects or branches, future runs, and peers with the head's timestamp are
excluded. Older runs tied on timestamp use descending run ID when selecting
the window; the response lists the selected runs chronologically.

Every test observed anywhere in that window is considered, including tests absent
from the head. Renaming a test or changing its relative report path creates a new
identity; discovery does not infer that the two names represent the same test.

This differs from `test_history`: that tool requires a test ID and selects the
latest runs in the branch. Discovery stays anchored to the specified head even
after newer runs are imported.

## Signals

| Signal | Rule |
| --- | --- |
| `INTERMITTENT_OUTCOMES` | At least `minTransitions` adjacent changes between passed and failed final outcomes; default 2 |
| `PASSED_AFTER_RETRY` | At least one passed testcase with a positive recorded retry-failure count |

Both signals may apply to the same test. An error and an assertion failure belong
to the same failed state, so changing from `ERROR` to `FAILURE` is not a transition.

For each pair of observations to count as a transition, the observations must
belong to adjacent selected runs, have strictly increasing timestamps, and both
have non-skipped outcomes. Missing and skipped observations break the sequence.
An equal-time pair has no known chronological order and is not counted.

| Timeline | Transitions |
| --- | --- |
| Passed → failed → passed | 2 |
| Passed → failed → error | 1 |
| Failed → missing → passed | 0 |
| Failed → skipped → passed | 0 |

The examples assume distinct timestamps. A final pass after a retry is a separate
signal, even when only one run exists. A test that still fails after retries does
not qualify for `PASSED_AFTER_RETRY`. The tool preserves the runner's retry count;
it does not infer missing attempts or normalize different runner conventions.

## Options and pagination

| CLI flag | MCP argument | Default | Allowed values |
| --- | --- | --- | --- |
| `--run` | `runId` | Required | An imported head run ID |
| `--window` | `window` | 20 | 3–50 runs, including head |
| `--min-transitions` | `minTransitions` | 2 | 2 through window minus 1 |
| `--offset` | `offset` | 0 | Nonnegative integer |
| `--limit` | `limit` | 20 | 1–20 candidates |
| `--snapshot` | `snapshot` | None | Hash returned by a previous response |

The CLI also accepts `--db PATH`. It returns JSON and exit 0 after a successful
query, including when candidates are found or the history is short. Invalid
options, unknown runs, and stale snapshots return exit 1 with details on stderr.
This is an investigation command, not a pass/fail CI gate.

Candidates are ordered by transition count descending, then retry-recovery run
count descending, failed final-outcome count descending, and test ID ascending.
This order helps review repeated signals; it is not a probability or severity score.

Follow `nextOffset` using the same run and policy and pass the returned `snapshot`.
A backfilled run that changes the selected window invalidates the snapshot.
Restart at offset zero without a snapshot to use the new selection. Newer imports
do not change an older head's analysis. Omitting the guard recomputes each page
against the currently available runs.

## Counts and evidence

These counts cover the whole selected window, regardless of the requested page:

| Field | Meaning |
| --- | --- |
| `totalTests` | Distinct test IDs observed at least once in the window |
| `intermittentTests` | Tests meeting the transition threshold |
| `retryRecoveryTests` | Tests with at least one final pass after reported retry failures |
| `totalCandidates` | Union of those two groups; a test is counted once |
| `shortHistoryTests` | Tests with fewer than `minTransitions + 1` non-skipped final observations |

Short histories can still contain a retry signal, so these categories overlap.
Enough observations alone does not guarantee comparable or adjacent results.
Zero candidates does not establish stability, and short-history counts do not
describe tests that have never appeared in any imported run.

Each candidate includes its class and name, counts of passed, failed, skipped,
and missing observations, the transition count, retry-recovery run count, and
total reported retry failures. `sameCommitTransitions` counts transitions whose
two runs name the same commit. That does not imply the same environment, test
data, or dependency resolution.

The `history` array has one entry for every selected run. Entries contain the
final outcome, retry count, and a `testledger://` evidence ID. Missing entries use
null outcome and evidence values. Resolve a citation through `test_evidence` to
inspect the stored report path, XML line, and redacted details. The `runs` array
provides commit and timestamp metadata without repeating it in every timeline.

## Reproduce the example

Build with `./gradlew installDist`, install the Node development dependencies,
and run `pnpm demo`. The existing four shopping-service fixtures contain:

- `rejectsDuplicate`: passed, failed, passed, failed — three transitions across
  different sample commits, with no claim about their cause.
- `retriesDelivery`: absent in the first three runs, then passed with one retry
  failure — a retry signal with a short history.

The demo calls the Kotlin server through the TypeScript MCP client and writes
`build/demo/stability.json`. Expect nine distinct tests, two candidates, one
intermittent test, one retry-recovery test, and three short histories. The fixture
is synthetic and the demo makes no model request.
