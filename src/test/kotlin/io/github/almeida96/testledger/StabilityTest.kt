package io.github.almeida96.testledger

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class StabilityTest {
    @TempDir lateinit var dir: Path
    private val db get() = dir.resolve("stability.db")
    private fun meta(id: String, second: Int, branch: String = "main", project: String = "shop", commit: String = "abc123") =
        RunMeta(id, project, branch, commit, Instant.parse("2026-10-31T12:00:00Z").plusSeconds(second.toLong()).toString())
    private fun row(name: String, outcome: Outcome = Outcome.PASSED, retries: Int = 0) =
        Observation(sha256(name).take(24), "TEST.xml", 2, "suite", "Shop", name, outcome, 0.1, "", "", "", if (outcome.failed) "failure" else null, retries)
    private fun Ledger.add(meta: RunMeta, vararg rows: Observation) = import(meta, ReportBatch(rows.toList(), 1, sha256(rows.joinToString())))

    @Test fun `discovers alternating outcomes and records which transitions shared a commit`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("a", 0), row("pay"))
            ledger.add(meta("b", 1), row("pay", Outcome.FAILURE))
            ledger.add(meta("c", 2), row("pay"))
            ledger.add(meta("head", 3, commit = "different"), row("pay", Outcome.ERROR))
        }
        Ledger(db).use { ledger ->
            val page = ledger.stability("head")
            assertEquals(1, page.totalTests); assertEquals(1, page.totalCandidates)
            assertEquals(1, page.intermittentTests); assertEquals(0, page.retryRecoveryTests)
            val candidate = page.candidates.single()
            assertEquals(listOf(StabilitySignal.INTERMITTENT_OUTCOMES), candidate.signals)
            assertEquals(3, candidate.transitions); assertEquals(2, candidate.sameCommitTransitions)
            assertEquals(2, candidate.passed); assertEquals(2, candidate.failed)
            assertEquals(listOf("a", "b", "c", "head"), candidate.history.map { it.runId })
            candidate.history.forEach { item ->
                assertEquals(ledger.test(item.runId, candidate.testId).outcome, item.outcome)
                assertEquals(evidence(item.runId, candidate.testId), item.evidence)
            }
        }
    }

    @Test fun `tests missing from the head remain discoverable with an explicit gap`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("a", 0), row("removed"))
            ledger.add(meta("b", 1), row("removed", Outcome.FAILURE))
            ledger.add(meta("c", 2), row("removed"))
            ledger.add(meta("head", 3), row("new"))
            val page = ledger.stability("head")
            assertEquals(2, page.totalTests); assertEquals(1, page.shortHistoryTests)
            val removed = page.candidates.single()
            assertEquals("removed", removed.name); assertEquals(1, removed.missing)
            assertNull(removed.history.last().outcome); assertNull(removed.history.last().evidence)
            assertEquals(2, removed.transitions)
        }
    }

    @Test fun `passed retries are separate signals and failed retries are not recoveries`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("head", 0), row("recovered", retries = 2), row("still-failing", Outcome.ERROR, retries = 4))
            val page = ledger.stability("head")
            assertEquals(0, page.intermittentTests); assertEquals(1, page.retryRecoveryTests)
            assertEquals(2, page.shortHistoryTests)
            val recovered = page.candidates.single()
            assertEquals(listOf(StabilitySignal.PASSED_AFTER_RETRY), recovered.signals)
            assertEquals(0, recovered.transitions); assertEquals(1, recovered.retryRecoveryRuns)
            assertEquals(2, recovered.retryFailures); assertEquals(2, recovered.history.single().retryFailures)
        }
    }

    @Test fun `missing skipped and failure to error observations cannot invent reversals`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("a", 0), row("pay", Outcome.FAILURE))
            ledger.add(meta("b", 1))
            ledger.add(meta("c", 2), row("pay"))
            ledger.add(meta("d", 3), row("pay", Outcome.SKIPPED))
            ledger.add(meta("e", 4), row("pay", Outcome.ERROR))
            ledger.add(meta("head", 5), row("pay", Outcome.FAILURE))
            assertEquals(0, ledger.stability("head").totalCandidates)
            assertEquals(0, ledger.history("shop", "main", row("pay").testId).transitions)
        }
    }

    @Test fun `equal timestamp ordering does not create transitions in either query`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("a", 0), row("pay"))
            ledger.add(meta("b", 0), row("pay", Outcome.FAILURE))
            ledger.add(meta("c", 0), row("pay"))
            ledger.add(meta("head", 1), row("pay"))
            assertEquals(0, ledger.stability("head").totalCandidates)
            assertEquals(0, ledger.history("shop", "main", row("pay").testId).transitions)
        }
    }

    @Test fun `head bounds exclude other scopes future runs and peers of the head`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("a", 0), row("pay"))
            ledger.add(meta("b", 1), row("pay", Outcome.FAILURE))
            ledger.add(meta("other-branch", 2, branch = "feature"), row("invisible", retries = 1))
            ledger.add(meta("other-project", 2, project = "other"), row("invisible", retries = 1))
            ledger.add(meta("peer", 3), row("invisible", retries = 1))
            ledger.add(meta("head", 3), row("pay"))
            ledger.add(meta("future", 4), row("invisible", retries = 1))
            val page = ledger.stability("head")
            assertEquals(listOf("a", "b", "head"), page.runs.map { it.id })
            assertEquals(1, page.totalTests); assertEquals("pay", page.candidates.single().name)
        }
    }

    @Test fun `window includes head and a stricter threshold still preserves retry signals`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("a", 0), row("pay"))
            ledger.add(meta("b", 1), row("pay", Outcome.FAILURE))
            ledger.add(meta("c", 2), row("pay"))
            ledger.add(meta("head", 3), row("pay"), row("retry", retries = 1))
            assertEquals(2, ledger.stability("head").totalCandidates)
            assertEquals("retry", ledger.stability("head", minTransitions = 3).candidates.single().name)
            val recent = ledger.stability("head", window = 3)
            assertEquals(listOf("b", "c", "head"), recent.runs.map { it.id })
            assertEquals("retry", recent.candidates.single().name)
        }
    }

    @Test fun `candidate ranking paginates consistently while counts cover all signals`() {
        Ledger(db, true).use { ledger ->
            for (index in 0..3) ledger.add(meta("r$index", index),
                row("both", if (index % 2 == 0) Outcome.PASSED else Outcome.FAILURE, retries = 1),
                row("alternating", if (index % 2 == 0) Outcome.PASSED else Outcome.FAILURE),
                row("retry-a", retries = 2), row("retry-b", retries = 2))
            val first = ledger.stability("r3", limit = 1)
            assertEquals("both", first.candidates.single().name)
            assertEquals(4, first.totalCandidates); assertEquals(2, first.intermittentTests); assertEquals(3, first.retryRecoveryTests)
            assertEquals(2, first.candidates.single().signals.size)
            val rest = ledger.stability("r3", offset = first.nextOffset!!, limit = 3, snapshot = first.snapshot)
            assertEquals("alternating", rest.candidates.first().name)
            assertEquals(rest.candidates.drop(1).map { it.testId }.sorted(), rest.candidates.drop(1).map { it.testId })
            assertNull(rest.nextOffset); assertEquals(4, rest.totalCandidates)
            val beyond = ledger.stability("r3", offset = Int.MAX_VALUE, snapshot = first.snapshot)
            assertEquals(4, beyond.totalCandidates); assertTrue(beyond.candidates.isEmpty()); assertNull(beyond.nextOffset)
        }
    }

    @Test fun `snapshots reject relevant backfills but survive unrelated imports`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("a", 0), row("pay")); ledger.add(meta("b", 2), row("pay", Outcome.FAILURE))
            ledger.add(meta("head", 4), row("pay"))
            val snapshot = ledger.stability("head").snapshot
            ledger.add(meta("future", 5), row("pay"))
            ledger.add(meta("other", 1, branch = "elsewhere"), row("pay"))
            assertEquals(snapshot, ledger.stability("head", snapshot = snapshot).snapshot)
            assertFails { ledger.stability("head", minTransitions = 3, snapshot = snapshot) }
            ledger.add(meta("backfill", 1))
            assertFailsWith<IllegalArgumentException> { ledger.stability("head", offset = 1, snapshot = snapshot) }
            assertNotEquals(snapshot, ledger.stability("head").snapshot)
            assertEquals(0, ledger.stability("head").totalCandidates)
        }
    }

    @Test fun `empty and short histories return explicit counts without claiming stable tests`() {
        Ledger(db, true).use { ledger ->
            ledger.add(meta("empty", 0))
            val empty = ledger.stability("empty")
            assertEquals(0, empty.totalTests); assertEquals(0, empty.totalCandidates); assertEquals(0, empty.shortHistoryTests)
            assertTrue(empty.candidates.isEmpty()); assertNull(empty.nextOffset)
            ledger.add(meta("head", 1), row("new"), row("skipped", Outcome.SKIPPED))
            val fresh = ledger.stability("head")
            assertEquals(2, fresh.totalTests); assertEquals(2, fresh.shortHistoryTests)
            assertEquals(0, fresh.totalCandidates)
        }
    }

    @Test fun `invalid options fail and the maximum window is supported`() {
        Ledger(db, true).use { ledger ->
            repeat(50) { ledger.add(meta("r$it", it), row("pay", if (it % 2 == 0) Outcome.PASSED else Outcome.ERROR)) }
            val largest = ledger.stability("r49", window = 50, minTransitions = 49)
            assertEquals(50, largest.runs.size); assertEquals(49, largest.candidates.single().transitions)
            assertFails { ledger.stability("r49", window = 2) }; assertFails { ledger.stability("r49", window = 51) }
            assertFails { ledger.stability("r49", minTransitions = 1) }; assertFails { ledger.stability("r49", window = 3, minTransitions = 3) }
            assertFails { ledger.stability("r49", offset = -1) }; assertFails { ledger.stability("r49", limit = 0) }
            assertFails { ledger.stability("r49", limit = 21) }; assertFails { ledger.stability("absent") }
        }
    }

    @Test fun `all four-run outcome combinations agree with an independent transition count`() {
        val outcomes = listOf(null, Outcome.PASSED, Outcome.FAILURE, Outcome.ERROR, Outcome.SKIPPED)
        val cases = (0 until 625).associate { number ->
            var encoded = number
            "case$number" to List(4) { outcomes[encoded % 5].also { encoded /= 5 } }
        }
        Ledger(db, true).use { ledger ->
            repeat(4) { index ->
                val rows = cases.mapNotNull { (name, sequence) -> sequence[index]?.let { row(name, it) } }
                ledger.add(meta("r$index", index), *rows.toTypedArray())
            }
            val expected = cases.mapNotNull { (name, sequence) ->
                val changes = sequence.zipWithNext().count { (a, b) ->
                    a != null && b != null && a != Outcome.SKIPPED && b != Outcome.SKIPPED && a.failed != b.failed
                }
                if (changes >= 2) name to changes else null
            }.toMap()
            val observed = mutableMapOf<String, Int>()
            var offset: Int? = 0
            while (offset != null) {
                val page = ledger.stability("r3", window = 4, offset = offset)
                assertEquals(624, page.totalTests); assertEquals(expected.size, page.totalCandidates)
                page.candidates.forEach { observed[it.name] = it.transitions }
                offset = page.nextOffset
            }
            assertEquals(expected, observed)
        }
    }
}
