package io.github.almeida96.testledger

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlin.test.*

class TimingsTest {
    @TempDir lateinit var dir: Path
    private val db get() = dir.resolve("timings.db")
    private fun meta(id: String, second: Int, branch: String = "main", project: String = "shop") =
        RunMeta(id, project, branch, "commit-$id", "2026-10-30T12:00:${second.toString().padStart(2, '0')}Z")
    private fun row(name: String, seconds: Double, outcome: Outcome = Outcome.PASSED, retries: Int = 0) =
        Observation(sha256(name).take(24), "TEST.xml", 2, "suite", "Shop", name, outcome, seconds, "", "", "", if (outcome.failed) "failure" else null, retries)
    private fun Ledger.add(meta: RunMeta, vararg rows: Observation) = import(meta, ReportBatch(rows.toList(), 1, sha256(rows.joinToString())))

    @Test fun `median resists a slow outlier and every sample resolves to stored evidence`() {
        Ledger(db, true).use { ledger ->
            listOf(1.0, 1.1, 1.2, 30.0).forEachIndexed { i, seconds -> ledger.add(meta("r$i", i), row("search", seconds)) }
            ledger.add(meta("head", 5), row("search", 2.0))
        }
        Ledger(db).use { ledger ->
            val page = ledger.timings("head")
            assertEquals(TimingStatus.REGRESSIONS, page.status)
            assertEquals(1, page.evaluatedTests)
            val regression = page.regressions.single()
            assertEquals(1.15, regression.medianSeconds)
            assertEquals(0.85, regression.increaseSeconds)
            assertEquals(listOf("r3", "r2", "r1", "r0"), regression.samples.map { it.runId })
            regression.samples.forEach { sample ->
                assertEquals(sample.seconds, ledger.test(sample.runId, regression.testId).seconds)
                assertEquals(evidence(sample.runId, regression.testId), sample.evidence)
            }
            assertEquals(evidence("head", regression.testId), regression.evidence)
        }
    }

    @Test fun `both thresholds apply and exact decimal boundaries are inclusive`() {
        Ledger(db, true).use { ledger ->
            repeat(3) { ledger.add(meta("r$it", it), row("tiny", 0.01), row("long", 10.0), row("boundary", 1.1)) }
            ledger.add(meta("head", 4), row("tiny", 0.05), row("long", 10.2), row("boundary", 1.2))
            assertEquals(TimingStatus.CLEAR, ledger.timings("head").status)
            val absoluteOnly = ledger.timings("head", TimingPolicy(minIncreasePercent = 0))
            assertEquals(setOf("long", "boundary"), absoluteOnly.regressions.map { it.name }.toSet())
            val relativeOnly = ledger.timings("head", TimingPolicy(minIncreaseMs = 0))
            assertEquals("tiny", relativeOnly.regressions.single().name)
            ledger.add(meta("exact", 5), row("boundary", 1.65))
            assertEquals(1, ledger.timings("exact", TimingPolicy(window = 3)).totalRegressions)
        }
    }

    @Test fun `failures skips retries and zero durations cannot become head candidates`() {
        Ledger(db, true).use { ledger ->
            repeat(3) { ledger.add(meta("r$it", it), row("failed", 1.0), row("skip", 1.0), row("retry", 1.0), row("zero", 1.0), row("ok", 1.0)) }
            ledger.add(meta("head", 4), row("failed", 20.0, Outcome.ERROR), row("skip", 20.0, Outcome.SKIPPED), row("retry", 20.0, retries = 1), row("zero", 0.0), row("ok", 1.0))
            val result = ledger.timings("head")
            assertEquals(5, result.totalTests); assertEquals(4, result.ignoredTests)
            assertEquals(1, result.eligibleTests); assertEquals(1, result.evaluatedTests)
            assertEquals(TimingStatus.CLEAR, result.status)
        }
    }

    @Test fun `unusable baseline observations are excluded without reaching beyond the window`() {
        Ledger(db, true).use { ledger ->
            repeat(3) { ledger.add(meta("good$it", it), row("pay", 1.0)) }
            ledger.add(meta("failed", 3), row("pay", 0.01, Outcome.FAILURE))
            ledger.add(meta("skipped", 4), row("pay", 0.01, Outcome.SKIPPED))
            ledger.add(meta("retry", 5), row("pay", 0.01, retries = 2))
            ledger.add(meta("zero", 6), row("pay", 0.0))
            ledger.add(meta("missing", 7))
            ledger.add(meta("head", 8), row("pay", 2.0))
            val page = ledger.timings("head")
            assertEquals(3, page.regressions.single().samples.size)
            val recent = ledger.timings("head", TimingPolicy(window = 5))
            assertEquals(5, recent.baselineRuns.size)
            assertEquals(0, recent.evaluatedTests); assertEquals(1, recent.insufficientSamples)
            assertEquals(TimingStatus.INSUFFICIENT_DATA, recent.status)
        }
    }

    @Test fun `baseline is strictly older and scoped to the heads project and branch`() {
        Ledger(db, true).use { ledger ->
            repeat(3) { ledger.add(meta("r$it", it), row("pay", 1.0)) }
            ledger.add(meta("branch", 3, branch = "feature"), row("pay", 100.0))
            ledger.add(meta("project", 3, project = "elsewhere"), row("pay", 100.0))
            ledger.add(meta("same-instant", 4).copy(at = "2026-10-30T09:00:04-03:00"), row("pay", 100.0))
            ledger.add(meta("future", 5), row("pay", 100.0))
            ledger.add(meta("head", 4), row("pay", 2.0))
            val page = ledger.timings("head")
            assertEquals(listOf("r2", "r1", "r0"), page.baselineRuns.map { it.id })
            assertEquals(1.0, page.regressions.single().medianSeconds)
        }
    }

    @Test fun `pages preserve overall totals and snapshots detect relevant backfills`() {
        Ledger(db, true).use { ledger ->
            repeat(3) { ledger.add(meta("r$it", it), row("large", 1.0), row("small", 1.0), row("equal", 1.0)) }
            ledger.add(meta("head", 8), row("large", 4.0), row("small", 2.0), row("equal", 2.0))
            val first = ledger.timings("head", limit = 1)
            assertEquals("large", first.regressions.single().name); assertEquals(3, first.totalRegressions)
            val second = ledger.timings("head", offset = first.nextOffset!!, limit = 2, snapshot = first.snapshot)
            assertEquals(second.regressions.map { it.testId }.sorted(), second.regressions.map { it.testId })
            assertNull(second.nextOffset); assertEquals(3, second.totalRegressions)
            val outside = ledger.timings("head", offset = Int.MAX_VALUE, snapshot = first.snapshot)
            assertEquals(TimingStatus.REGRESSIONS, outside.status); assertTrue(outside.regressions.isEmpty()); assertNull(outside.nextOffset)
            ledger.add(meta("later", 9), row("large", 20.0))
            ledger.add(meta("unrelated", 7, branch = "other"), row("large", 20.0))
            assertEquals(first.snapshot, ledger.timings("head", snapshot = first.snapshot).snapshot)
            ledger.add(meta("backfill", 6), row("large", 20.0))
            assertFailsWith<IllegalArgumentException> { ledger.timings("head", offset = 1, snapshot = first.snapshot) }
            assertNotEquals(first.snapshot, ledger.timings("head").snapshot)
        }
    }

    @Test fun `no history empty runs and partial coverage do not report clear`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("empty", 0))
            assertEquals(TimingStatus.INSUFFICIENT_DATA, ledger.timings("empty").status)
            ledger.add(meta("fresh", 1), row("new", 1.0))
            assertEquals(TimingStatus.INSUFFICIENT_DATA, ledger.timings("fresh").status)
            repeat(3) { ledger.add(meta("r$it", it + 2), row("old", 1.0)) }
            ledger.add(meta("head", 6), row("old", 1.0), row("new", 2.0))
            val page = ledger.timings("head")
            assertEquals(1, page.evaluatedTests); assertEquals(1, page.insufficientSamples)
            assertEquals(TimingStatus.INSUFFICIENT_DATA, page.status)
        }
    }

    @Test fun `invalid policies pagination runs and snapshot reuse fail explicitly`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("head", 4), row("pay", 1.0))
            for (policy in listOf(TimingPolicy(window = 2), TimingPolicy(window = 51), TimingPolicy(minSamples = 2), TimingPolicy(window = 3, minSamples = 4), TimingPolicy(minIncreaseMs = -1), TimingPolicy(minIncreasePercent = -1))) {
                assertFailsWith<IllegalArgumentException> { ledger.timings("head", policy) }
            }
            assertFails { ledger.timings("head", offset = -1) }; assertFails { ledger.timings("head", limit = 21) }
            assertFails { ledger.timings("head", limit = 0) }; assertFails { ledger.timings("absent") }
            val snapshot = ledger.timings("head").snapshot
            assertFails { ledger.timings("head", TimingPolicy(minIncreaseMs = 200), snapshot = snapshot) }
        }
    }

    @Test fun `very large and subnormal finite durations produce serializable results`() {
        Ledger(db, true).use { ledger ->
            repeat(4) { ledger.add(meta("r$it", it), row("huge", 1.0e308), row("tiny", Double.MIN_VALUE)) }
            ledger.add(meta("head", 5), row("huge", Double.MAX_VALUE), row("tiny", 1.0))
            val page = ledger.timings("head")
            assertEquals(2, page.totalRegressions)
            assertTrue(page.regressions.all { it.medianSeconds.isFinite() && it.increaseSeconds.isFinite() })
            assertFalse(json.encodeToString(page).contains("Infinity"))
        }
    }
}
