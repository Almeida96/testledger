# Investigating the example runs

Run `./gradlew installDist`, install the optional Node development dependencies,
then run `pnpm demo`. Use `gradlew.bat` on Windows.

The four XML directories under `examples` describe a fictional shopping service.
They contain deliberately chosen outcomes so the investigation can be reproduced
without a CI account, provider key, or model response.

1. `list_runs` identifies `run-4` and `run-3` in project `shop`, branch `main`.
2. `compare_runs` finds two regressions, one persistent failure, one recovered test,
   one new failure, one failure after a skip, one newly skipped test, and one absent test.
3. `failure_groups` puts the two order-validation regressions in one group because
   they share an exception type and application stack location. Inspect the
   observations before inferring that their causes are the same.
4. `test_history` shows `rejectsDuplicate` alternating passed, failed, passed,
   failed. This is three adjacent transitions across four different sample commits.
   A changing implementation could explain the sequence; flakiness is not proven.
5. `test_evidence` for `opensPool` shows the persistent database timeout and its
   report line. The example bearer credential is redacted before SQLite storage.

The `triage-tests` MCP prompt asks an agent to follow this sequence and cite
`testledger://` evidence IDs. An appropriate conclusion is that two tests share
a failure signature and deserve a joint inspection, while the database timeout
already existed in the baseline. The reports do not establish why either failure
occurred, so a useful next check would be the relevant code diff or environment
configuration, provided by the user through a separate authorized tool.

The generated Markdown report is deterministic. It can be attached to a PR, but
TestLedger does not post comments or open issues automatically.
