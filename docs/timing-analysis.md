# Finding slower tests

`timings` compares a run's passed testcases with their earlier measurements.
`timing-check` performs the same analysis and returns an exit code for CI.
The MCP tool is named `performance_regressions`.

```sh
testledger timings --run build-142 --window 10 --min-samples 3 \
  --min-increase-ms 100 --min-increase-percent 50
```

The default policy looks at the ten most recent runs strictly before `build-142`
in the same project and branch. Each head test needs at least three usable
samples. A result is reported only when its duration exceeds the sample median
by **both** 100 milliseconds and 50%. Threshold equality qualifies; an unchanged
duration never does, even when both thresholds are zero.

For example, samples of 1.0, 1.1, 1.2, and 30 seconds have a median of 1.15
seconds. A head duration of 2 seconds is 0.85 seconds above that baseline and
meets both default thresholds. This is a reason to investigate, not proof that
the head commit introduced a performance bug.

## Which measurements count

- The head and baseline testcase must have outcome `PASSED`, no retry hints,
  and a positive finite duration. Failed, skipped, retried, zero, and absent
  durations are ignored. JUnit XML without a time value is stored as zero.
- Test identity includes the report path, suite, class, and test name. Keep report
  layouts stable between imports. A renamed or moved test starts a new history.
- The window counts runs, not usable samples. Missing or ignored observations
  reduce the sample count; the query does not keep searching further back.
- Equal timestamps and future runs are excluded. Equal timestamps among older
  baseline runs are ordered by run ID descending before applying the window.
- A baseline may contain different commits. Use separate project scopes for
  different runners, operating systems, or test configurations.

The median is the middle sorted sample, or the mean of the two middle samples
for an even count. It reduces the effect of isolated outliers. There is no
significance test, causal attribution, or controlled benchmark in this command.

## Options

| CLI flag | MCP argument | Default | Allowed values |
| --- | --- | --- | --- |
| `--run` | `runId` | Required | An imported run ID |
| `--window` | `window` | 10 | 3–50 runs |
| `--min-samples` | `minSamples` | 3 | 3 through window |
| `--min-increase-ms` | `minIncreaseMs` | 100 | Integer, 0–2147483647 |
| `--min-increase-percent` | `minIncreasePercent` | 50 | Integer, 0–2147483647 |
| `--offset` | `offset` | 0 | Nonnegative integer |
| `--limit` | `limit` | 20 | 1–20 regressions |
| `--snapshot` | `snapshot` | None | Hash from a previous response |

Every CLI command also accepts `--db PATH`.

## Reading the result

The JSON response includes the head's metadata, the policy, ordered baseline run
metadata, and these counts for the entire analysis:

| Field | Meaning |
| --- | --- |
| `totalTests` | All observations in the head run |
| `eligibleTests` | Passed head tests with a positive duration and no retry hints |
| `evaluatedTests` | Eligible tests with enough usable baseline samples |
| `insufficientSamples` | Eligible tests without enough baseline samples |
| `ignoredTests` | Head tests excluded before comparison |
| `totalRegressions` | Evaluated tests meeting both thresholds |

`eligibleTests = evaluatedTests + insufficientSamples` and
`totalTests = eligibleTests + ignoredTests`.

Each item in `regressions` contains head seconds, baseline median seconds,
increase seconds, and a head evidence ID. Its `samples` list contains every
measurement used for that test, newest first, with its run ID and evidence ID.
Resolve those IDs through `test_evidence`. The page is sorted by absolute
increase descending, then test ID.

Follow `nextOffset` with the **same options and returned `snapshot`**. If an older
run is imported between pages and changes the baseline, the guard rejects the
request. Restart at offset zero without a snapshot. Omitting the guard recomputes
against the currently available baseline. The hash is a consistency check, not
an authentication token or a saved database snapshot.

## Use in CI

Import completed reports into a ledger that already contains earlier runs, then
run the gate. A fresh database has no usable baseline. Retain the database
between builds or re-import historical reports with their original metadata;
do not add the ledger to source control.

```sh
testledger timing-check --db /data/ledger.db --run build-142 \
  --window 10 --min-samples 3 --min-increase-ms 200 \
  --min-increase-percent 50 > timing-results.json
```

| Exit | Status | Meaning |
| --- | --- | --- |
| 0 | `CLEAR` | At least one eligible test was evaluated, all eligible tests had enough samples, and none met both thresholds |
| 1 | No analysis | Invalid options, missing ledger/run, stale snapshot, or another command error; details go to stderr |
| 2 | `REGRESSIONS` | At least one regression was found; other eligible tests may still lack history |
| 3 | `INSUFFICIENT_DATA` | No regression was found, but no test could be evaluated or at least one eligible test lacked samples |

The gate considers all eligible head tests even when `--limit` is small or
`--offset` is beyond the last result. Exit 0 covers eligible tests only: review
`ignoredTests` alongside the test runner's own result. New tests often cause
exit 3 until they accumulate enough history; choose explicitly whether your CI
treats that as blocking or advisory. `timings` returns exit 0 for any successful
analysis, including regressions or insufficient data.

## Reproduce the fixture

Build with `./gradlew installDist`, install the Node development dependencies,
and run `pnpm demo`. The five directories under `examples/timings` are synthetic
reports for project `catalog`. They show one slower search test, a stable cache
test, an excluded retry, an unmeasured test, and a new test lacking history.

The demo calls the Kotlin server through an independent MCP client and writes
`build/demo/timings.json`. It should report one regression, two evaluated tests,
one test with insufficient samples, and two ignored tests. It makes no model
request and does not infer a root cause.
