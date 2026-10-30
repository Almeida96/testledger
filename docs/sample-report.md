# Test run run-4

Project: shop · Branch: main · Commit: sample-4

8 tests: 2 passed, 5 failed, 1 skipped. Testcases with retry hints: 1.

Compared with run-3. Missing and skipped tests are not counted as recovered.

| Change | Test | Evidence |
| --- | --- | --- |
| failed_after_skip | validatesAddress | `testledger://runs/run-3/tests/f594a395ba0c9767a3ca46b2` `testledger://runs/run-4/tests/f594a395ba0c9767a3ca46b2` |
| new_failure | readsNewVersion | `testledger://runs/run-4/tests/35aa637db98b16a649a89b88` |
| not_observed | exportsLegacyOrder | `testledger://runs/run-3/tests/3bf924f2da0bb0bb8875576b` |
| now_skipped | reservesStock | `testledger://runs/run-3/tests/747cbfeae3e45c045799e0d9` `testledger://runs/run-4/tests/747cbfeae3e45c045799e0d9` |
| persistent_failure | opensPool | `testledger://runs/run-3/tests/9197f5f96476b40cccd8dfb4` `testledger://runs/run-4/tests/9197f5f96476b40cccd8dfb4` |
| recovered | deliversReceipt | `testledger://runs/run-3/tests/957d0f2b8637aea43ffcce61` `testledger://runs/run-4/tests/957d0f2b8637aea43ffcce61` |
| regression | rejectsDuplicate | `testledger://runs/run-3/tests/12e1bca22e5077c7f7be1fb7` `testledger://runs/run-4/tests/12e1bca22e5077c7f7be1fb7` |
| regression | declinesInvalidTotal | `testledger://runs/run-3/tests/f4c5b5cd676467043fc562b3` `testledger://runs/run-4/tests/f4c5b5cd676467043fc562b3` |

Evidence IDs resolve through the test_evidence MCP tool using the run and test IDs. This report describes observed outcomes; it does not determine root causes.
