package io.github.almeida96.testledger

import kotlinx.serialization.encodeToString
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

class Ledger(path: Path, private val writable: Boolean = false) : AutoCloseable {
    private val connection: Connection
    init {
        require(writable || Files.isRegularFile(path)) { "Ledger not found. Import a run first." }
        if (writable) Files.createDirectories(path.toAbsolutePath().parent)
        val config = SQLiteConfig().apply { setReadOnly(!writable); setBusyTimeout(5000); enforceForeignKeys(true) }
        connection = config.createConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        val version = connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        require(version in 0..1) { "Unsupported ledger schema: $version" }
        if (writable) connection.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("CREATE TABLE IF NOT EXISTS runs (id TEXT PRIMARY KEY, project TEXT NOT NULL, branch TEXT NOT NULL, at TEXT NOT NULL, meta TEXT NOT NULL, digest TEXT NOT NULL, reports INTEGER NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS observations (run_id TEXT NOT NULL REFERENCES runs(id), test_id TEXT NOT NULL, outcome TEXT NOT NULL, retries INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id, test_id))")
            s.execute("CREATE INDEX IF NOT EXISTS run_scope ON runs(project, branch, at)")
            s.execute("PRAGMA user_version=1")
        }
        else require(version == 1) { "Ledger schema is missing. Import a run first." }
    }
    @Synchronized override fun close() = connection.close()

    @Synchronized fun import(meta: RunMeta, batch: ReportBatch): ImportResult {
        check(writable) { "This ledger is read-only" }
        meta.validate()
        val canonical = meta.copy(at = DateTimeFormatterBuilder().appendInstant(9).toFormatter().format(Instant.parse(meta.at)))
        require(batch.tests.map { it.testId }.toSet().size == batch.tests.size) { "Duplicate test identity" }
        connection.autoCommit = false
        try {
            val previous = query("SELECT meta, digest, reports FROM runs WHERE id=?", canonical.id) {
                Triple(it.getString(1), it.getString(2), it.getInt(3))
            }.singleOrNull()
            if (previous != null) {
                require(previous.first == json.encodeToString(canonical) && previous.second == batch.digest) { "Run ID already exists with different reports or metadata; use a new ID" }
                connection.rollback()
                return ImportResult(canonical, false, previous.third, batch.tests.size, batch.digest)
            }
            connection.prepareStatement("INSERT INTO runs VALUES (?, ?, ?, ?, ?, ?, ?)").use { s ->
                listOf(canonical.id, canonical.project, canonical.branch, canonical.at, json.encodeToString(canonical), batch.digest).forEachIndexed { i, v -> s.setString(i + 1, v) }
                s.setInt(7, batch.reportCount); s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO observations VALUES (?, ?, ?, ?, ?)").use { s ->
                for (test in batch.tests) {
                    s.setString(1, canonical.id); s.setString(2, test.testId); s.setString(3, test.outcome.name)
                    s.setInt(4, test.retryFailures); s.setString(5, json.encodeToString(test)); s.addBatch()
                }
                s.executeBatch()
            }
            connection.commit()
            return ImportResult(canonical, true, batch.reportCount, batch.tests.size, batch.digest)
        } catch (e: Exception) { connection.rollback(); throw e }
        finally { connection.autoCommit = true }
    }

    @Synchronized fun runs(project: String, branch: String, offset: Int = 0, limit: Int = 20): RunPage {
        page(offset, limit, 50)
        val records = query("SELECT meta FROM runs WHERE project=? AND branch=? ORDER BY at DESC, id DESC LIMIT ? OFFSET ?", project, branch, limit + 1, offset) {
            json.decodeFromString<RunMeta>(it.getString(1))
        }
        return RunPage(records.take(limit).map { summary(it.id) }, if (records.size > limit) offset + limit else null)
    }

    @Synchronized fun summary(id: String): RunSummary {
        val meta = run(id)
        val counts = query("SELECT outcome, count(*) FROM observations WHERE run_id=? GROUP BY outcome", id) { it.getString(1) to it.getInt(2) }.toMap()
        val retries = query("SELECT count(*) FROM observations WHERE run_id=? AND retries>0", id) { it.getInt(1) }.single()
        return RunSummary(meta, counts.values.sum(), counts["PASSED"] ?: 0, (counts["FAILURE"] ?: 0) + (counts["ERROR"] ?: 0), counts["SKIPPED"] ?: 0, retries)
    }

    @Synchronized fun test(runId: String, testId: String): Observation {
        run(runId)
        return findTest(runId, testId) ?: error("Test is not present in this run")
    }

    @Synchronized fun failures(runId: String, offset: Int = 0, limit: Int = 10): FailurePage {
        page(offset, limit, 20); run(runId)
        val groups = tests(runId).filter { it.outcome.failed }.groupBy { it.fingerprint!! }.map { (fp, cases) ->
            FailureGroup(fp, cases.first().failureType, cases.size, cases.sortedBy { it.testId }.take(3).map {
                TestRef(it.testId, it.className, it.name, it.outcome, evidence(runId, it.testId))
            })
        }.sortedWith(compareByDescending<FailureGroup> { it.count }.thenBy { it.fingerprint })
        return FailurePage(runId, groups.drop(offset).take(limit), groups.size, if (offset + limit < groups.size) offset + limit else null)
    }

    @Synchronized fun compare(baseId: String, headId: String, offset: Int = 0, limit: Int = 50): Comparison {
        page(offset, limit, 100)
        val base = run(baseId); val head = run(headId)
        require(baseId != headId) { "Choose two different runs" }
        require(base.project == head.project && base.branch == head.branch) { "Compare runs from the same project and branch" }
        require(Instant.parse(base.at) < Instant.parse(head.at)) { "Base run must be older than head run" }
        val before = tests(baseId).associateBy { it.testId }; val after = tests(headId).associateBy { it.testId }
        val changes = (before.keys + after.keys).sorted().mapNotNull { id ->
            val a = before[id]; val b = after[id]
            val kind = when {
                b?.outcome?.failed == true && a?.outcome == Outcome.PASSED -> "regression"
                b?.outcome?.failed == true && a?.outcome?.failed == true -> "persistent_failure"
                b?.outcome?.failed == true && a == null -> "new_failure"
                b?.outcome?.failed == true && a?.outcome == Outcome.SKIPPED -> "failed_after_skip"
                a?.outcome?.failed == true && b?.outcome == Outcome.PASSED -> "recovered"
                a?.outcome?.failed == true && b?.outcome == Outcome.SKIPPED -> "now_skipped"
                a?.outcome?.failed == true && b == null -> "not_observed"
                else -> null
            }
            kind?.let { Change(it, id, (b ?: a)!!.name, a?.outcome, b?.outcome,
                listOfNotNull(a?.let { evidence(baseId, id) }, b?.let { evidence(headId, id) })) }
        }.sortedWith(compareBy<Change> { it.kind }.thenBy { it.testId })
        return Comparison(baseId, headId, changes.groupingBy { it.kind }.eachCount(), changes.drop(offset).take(limit), if (offset + limit < changes.size) offset + limit else null)
    }

    @Synchronized fun history(project: String, branch: String, testId: String, window: Int = 20): TestHistory {
        require(window in 2..50) { "History window must be between 2 and 50 runs" }
        val runs = query("SELECT meta FROM runs WHERE project=? AND branch=? ORDER BY at DESC, id DESC LIMIT ?", project, branch, window) {
            json.decodeFromString<RunMeta>(it.getString(1))
        }.reversed()
        val items = runs.map { r ->
            val observation = findTest(r.id, testId)
            HistoryItem(r, observation?.outcome, observation?.retryFailures ?: 0, observation?.let { evidence(r.id, testId) })
        }
        val observed = items.count { it.outcome != null }
        require(observed > 0) { "Test is not present in the selected history window" }
        // Missing and skipped cases break the sequence; neither is evidence of a successful run.
        val transitions = items.zipWithNext().count { (a, b) ->
            a.outcome != null && b.outcome != null && a.outcome != Outcome.SKIPPED && b.outcome != Outcome.SKIPPED && a.outcome.failed != b.outcome.failed
        }
        return TestHistory(testId, "$project / $branch", observed, items.size - observed, transitions, transitions >= 2,
            "Two or more adjacent pass/fail transitions indicate an intermittent outcome in this window. They do not establish flakiness or a root cause; commits and environments may differ.", items)
    }

    private fun run(id: String): RunMeta = query("SELECT meta FROM runs WHERE id=?", id) { json.decodeFromString<RunMeta>(it.getString(1)) }
        .singleOrNull() ?: error("Run not found: $id")
    private fun tests(id: String): List<Observation> = query("SELECT payload FROM observations WHERE run_id=? ORDER BY test_id", id) { json.decodeFromString<Observation>(it.getString(1)) }
    private fun findTest(run: String, test: String): Observation? = query("SELECT payload FROM observations WHERE run_id=? AND test_id=?", run, test) { json.decodeFromString<Observation>(it.getString(1)) }.singleOrNull()
    private fun <T> query(sql: String, vararg args: Any, map: (ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, value) }
        s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
    }
    private fun page(offset: Int, limit: Int, max: Int) { require(offset >= 0 && limit in 1..max) { "Invalid pagination: offset >= 0, limit 1–$max" } }
}
