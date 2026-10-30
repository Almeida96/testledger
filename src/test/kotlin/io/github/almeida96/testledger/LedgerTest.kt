package io.github.almeida96.testledger

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class LedgerTest {
    @TempDir lateinit var dir: Path
    private val db get() = dir.resolve("ledger.db")
    private fun meta(id: String, second: Int, branch: String = "main") = RunMeta(id, "shop", branch, "abc123", "2026-10-30T12:00:${second.toString().padStart(2, '0')}Z")
    private fun row(name: String, outcome: Outcome) = Observation(sha256(name).take(24), "TEST.xml", 2, "suite", "Shop", name, outcome, 0.1, "AssertionError", "expected 1", "at app.Shop.total(Shop.kt:12)", if (outcome.failed) "shared-fingerprint" else null)
    private fun batch(vararg rows: Observation) = ReportBatch(rows.toList(), 1, sha256(rows.joinToString()))

    @Test fun `import survives reopening and identical retries are idempotent`() {
        val b = batch(row("pay", Outcome.FAILURE))
        Ledger(db, true).use { assertTrue(it.import(meta("r1", 1), b).inserted); assertFalse(it.import(meta("r1", 1), b).inserted) }
        Ledger(db).use { assertEquals(1, it.summary("r1").failed); assertEquals("pay", it.test("r1", b.tests.single().testId).name) }
    }
    @Test fun `conflicting run IDs never overwrite stored evidence`() {
        Ledger(db, true).use {
            it.import(meta("r1", 1), batch(row("pay", Outcome.FAILURE)))
            assertFails { it.import(meta("r1", 1), batch(row("pay", Outcome.PASSED))) }
            assertEquals(1, it.summary("r1").failed)
        }
    }
    @Test fun `read only ledger cannot import`() {
        Ledger(db, true).use { it.import(meta("r1", 1), batch()) }
        Ledger(db).use { assertFails { it.import(meta("r2", 2), batch()) } }
    }
    @Test fun `comparison distinguishes seven failure changes and does not invent recovery`() {
        Ledger(db, true).use {
            val a = batch(row("regressed", Outcome.PASSED), row("persistent", Outcome.FAILURE), row("fixed", Outcome.ERROR), row("missing", Outcome.FAILURE), row("skipped", Outcome.FAILURE), row("unverified", Outcome.SKIPPED))
            val b = batch(row("regressed", Outcome.ERROR), row("persistent", Outcome.FAILURE), row("fixed", Outcome.PASSED), row("skipped", Outcome.SKIPPED), row("unverified", Outcome.FAILURE), row("new", Outcome.FAILURE))
            it.import(meta("base", 1), a); it.import(meta("head", 2), b)
            val diff = it.compare("base", "head")
            assertEquals(setOf("regression", "persistent_failure", "recovered", "not_observed", "now_skipped", "failed_after_skip", "new_failure"), diff.counts.keys)
            assertTrue(diff.counts.values.all { n -> n == 1 })
            assertTrue(diff.changes.all { c -> c.evidence.isNotEmpty() })
        }
    }
    @Test fun `comparison rejects cross branch reversed and identical runs`() {
        Ledger(db, true).use {
            it.import(meta("a", 1), batch()); it.import(meta("b", 2), batch()); it.import(meta("c", 3, "feature"), batch())
            assertFails { it.compare("b", "a") }; assertFails { it.compare("a", "a") }; assertFails { it.compare("a", "c") }
        }
    }
    @Test fun `grouping returns bounded examples with evidence IDs`() {
        Ledger(db, true).use {
            it.import(meta("r", 1), batch(*(1..7).map { n -> row("case$n", Outcome.FAILURE) }.toTypedArray()))
            val group = it.failures("r").groups.single()
            assertEquals(7, group.count); assertEquals(3, group.examples.size)
            assertTrue(group.examples.all { e -> e.evidence.startsWith("testledger://runs/r/tests/") })
        }
    }
    @Test fun `history counts adjacent reversals but does not label one regression intermittent`() {
        Ledger(db, true).use {
            it.import(meta("a", 1), batch(row("pay", Outcome.PASSED))); it.import(meta("b", 2), batch(row("pay", Outcome.FAILURE)))
            val id = row("pay", Outcome.PASSED).testId
            assertFalse(it.history("shop", "main", id).intermittent)
            it.import(meta("c", 3), batch(row("pay", Outcome.PASSED)))
            assertEquals(2, it.history("shop", "main", id).transitions); assertTrue(it.history("shop", "main", id).intermittent)
        }
    }
    @Test fun `missing and skipped observations break transitions and branches are isolated`() {
        Ledger(db, true).use {
            it.import(meta("a", 1), batch(row("pay", Outcome.FAILURE)))
            it.import(meta("b", 2), batch()); it.import(meta("c", 3), batch(row("pay", Outcome.PASSED)))
            it.import(meta("d", 4), batch(row("pay", Outcome.SKIPPED))); it.import(meta("e", 5), batch(row("pay", Outcome.FAILURE)))
            it.import(meta("f", 6, "other"), batch(row("pay", Outcome.PASSED)))
            val history = it.history("shop", "main", row("pay", Outcome.PASSED).testId)
            assertEquals(0, history.transitions); assertEquals(1, history.missing); assertEquals(5, history.history.size)
        }
    }
    @Test fun `ordering handles fractional timestamps offsets and pagination`() {
        Ledger(db, true).use {
            it.import(meta("whole", 1), batch())
            it.import(meta("fraction", 1).copy(at = "2026-10-30T09:00:01.01-03:00"), batch())
            assertEquals("fraction", it.runs("shop", "main", limit = 1).runs.single().run.id)
            assertEquals(1, it.runs("shop", "main", limit = 1).nextOffset)
            assertNull(it.runs("shop", "main", offset = 1, limit = 1).nextOffset)
        }
    }
    @Test fun `invalid query limits unknown runs and duplicate rows fail`() {
        Ledger(db, true).use {
            assertFails { it.runs("shop", "main", -1) }; assertFails { it.failures("unknown") }
            assertFails { it.history("shop", "main", "x", 500) }
            val row = row("x", Outcome.PASSED); assertFails { it.import(meta("bad", 1), batch(row, row)) }
            assertEquals(0, it.runs("shop", "main").runs.size)
        }
    }
    @Test fun `MCP dispatch validates types unknown tools and unexpected arguments`() {
        Ledger(db, true).use {
            it.import(meta("a", 1), batch(row("pay", Outcome.FAILURE)))
            val tools = LedgerTools(it)
            val data = tools.call("run_summary", buildJsonObject { put("runId", "a") })
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            assertFails { tools.call("delete_runs", buildJsonObject {}) }
            assertFails { tools.call("run_summary", buildJsonObject { put("runId", "a"); put("extra", "ignored") }) }
            assertFails { tools.call("failure_groups", buildJsonObject { put("runId", "a"); put("limit", "10") }) }
        }
    }
    @Test fun `Markdown report escapes document markup and includes every change page`() {
        Ledger(db, true).use {
            val rows = (1..105).map { n -> row("<script>|[item]$n", Outcome.FAILURE) }
            it.import(meta("a", 1), batch()); it.import(meta("b", 2), batch(*rows.toTypedArray()))
            val report = markdownReport(it, "a", "b")
            assertFalse(report.contains("<script>")); assertContains(report, "&lt;script&gt;")
            assertEquals(105, report.lineSequence().count { line -> line.startsWith("| new_failure") })
        }
    }
    @Test fun `invalid report batch cannot create a run through CLI`() {
        val reports = Files.createDirectory(dir.resolve("reports"))
        Files.writeString(reports.resolve("TEST-a.xml"), "<testsuite><testcase name=\"ok\"/></testsuite>")
        Files.writeString(reports.resolve("TEST-z.xml"), "<broken")
        assertFails { execute(arrayOf("import", "--reports", reports.toString(), "--db", db.toString(), "--id", "x", "--project", "shop", "--branch", "main", "--commit", "abc", "--at", "2026-10-30T12:00:00Z")) }
        assertFalse(Files.exists(db))
    }
}
