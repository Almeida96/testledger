package io.github.almeida96.testledger

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.sql.Connection
import java.sql.ResultSet

@Serializable
enum class StabilitySignal { INTERMITTENT_OUTCOMES, PASSED_AFTER_RETRY }

@Serializable
data class StabilityObservation(val runId: String, val outcome: Outcome?, val retryFailures: Int, val evidence: String?)

@Serializable
data class StabilityCandidate(
    val testId: String,
    val className: String,
    val name: String,
    val signals: List<StabilitySignal>,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val missing: Int,
    val transitions: Int,
    val sameCommitTransitions: Int,
    val retryRecoveryRuns: Int,
    val retryFailures: Int,
    val history: List<StabilityObservation>,
)

@Serializable
data class StabilityPage(
    val head: RunMeta,
    val window: Int,
    val minTransitions: Int,
    val runs: List<RunMeta>,
    val snapshot: String,
    val totalTests: Int,
    val intermittentTests: Int,
    val retryRecoveryTests: Int,
    val shortHistoryTests: Int,
    val totalCandidates: Int,
    val candidates: List<StabilityCandidate>,
    val nextOffset: Int?,
    val note: String = "Signals describe recorded outcomes, not proven flakiness or root cause. Missing, skipped, and equal-time observations break transitions. Same-commit changes can still reflect different environments. No candidates does not establish stability.",
)

// Aggregate and paginate in SQLite so the JVM does not load every test's history.
internal class StabilityQuery(private val connection: Connection) {
    fun read(head: RunMeta, runs: List<RunMeta>, window: Int, minTransitions: Int, offset: Int, limit: Int, snapshot: String?): StabilityPage {
        val selectedSnapshot = sha256("stability-v1\n$window\n$minTransitions\n${json.encodeToString(runs)}")
        require(snapshot == null || snapshot == selectedSnapshot) { "Stability snapshot changed. Restart at offset 0 without a snapshot." }
        val args = runs.flatMapIndexed { index, run -> listOf(run.id, index, run.at, run.commit) } +
            listOf(minTransitions, minTransitions + 1, minTransitions, minTransitions, limit, offset)
        val sql = """
            WITH selected_runs(id, position, at, commit_ref) AS (VALUES ${runs.joinToString { "(?, ?, ?, ?)" }}),
            sequenced AS (
                SELECT o.test_id, o.outcome, o.retries, s.position, s.at, s.commit_ref,
                    LAG(o.outcome) OVER history AS previous_outcome,
                    LAG(s.position) OVER history AS previous_position,
                    LAG(s.at) OVER history AS previous_at,
                    LAG(s.commit_ref) OVER history AS previous_commit
                FROM selected_runs s JOIN observations o ON o.run_id=s.id
                WINDOW history AS (PARTITION BY o.test_id ORDER BY s.position)
            ), changes AS (
                SELECT *, CASE WHEN position=previous_position+1 AND at>previous_at
                    AND outcome!='SKIPPED' AND previous_outcome!='SKIPPED'
                    AND (outcome='PASSED')!=(previous_outcome='PASSED') THEN 1 ELSE 0 END AS changed
                FROM sequenced
            ), stats AS (
                SELECT test_id, COUNT(*) AS observed,
                    SUM(outcome='PASSED') AS passed, SUM(outcome IN ('FAILURE','ERROR')) AS failed,
                    SUM(outcome='SKIPPED') AS skipped, SUM(changed) AS transitions,
                    SUM(changed=1 AND commit_ref=previous_commit) AS same_commit_transitions,
                    SUM(outcome='PASSED' AND retries>0) AS retry_recovery_runs, SUM(retries) AS retry_failures
                FROM changes GROUP BY test_id
            ), totals AS (
                SELECT COUNT(*) AS total_tests,
                    COALESCE(SUM(transitions>=?), 0) AS intermittent_tests,
                    COALESCE(SUM(retry_recovery_runs>0), 0) AS retry_recovery_tests,
                    COALESCE(SUM(passed+failed<?), 0) AS short_history_tests,
                    COALESCE(SUM(transitions>=? OR retry_recovery_runs>0), 0) AS total_candidates
                FROM stats
            ), page AS (
                SELECT * FROM stats WHERE transitions>=? OR retry_recovery_runs>0
                ORDER BY transitions DESC, retry_recovery_runs DESC, failed DESC, test_id
                LIMIT ? OFFSET ?
            )
            SELECT * FROM totals LEFT JOIN page ON 1=1
            ORDER BY transitions DESC, retry_recovery_runs DESC, failed DESC, test_id
        """.trimIndent()
        val rows = query(sql, args) { r ->
            val totals = intArrayOf(r.getInt("total_tests"), r.getInt("intermittent_tests"), r.getInt("retry_recovery_tests"), r.getInt("short_history_tests"), r.getInt("total_candidates"))
            val id = r.getString("test_id")
            val candidate = id?.let {
                StabilityCandidate(it, "", "", buildList {
                    if (r.getInt("transitions") >= minTransitions) add(StabilitySignal.INTERMITTENT_OUTCOMES)
                    if (r.getInt("retry_recovery_runs") > 0) add(StabilitySignal.PASSED_AFTER_RETRY)
                }, r.getInt("passed"), r.getInt("failed"), r.getInt("skipped"), runs.size - r.getInt("observed"),
                    r.getInt("transitions"), r.getInt("same_commit_transitions"), r.getInt("retry_recovery_runs"), r.getInt("retry_failures"), emptyList())
            }
            totals to candidate
        }
        val totals = rows.first().first
        val candidates = rows.mapNotNull { it.second }
        val observations = if (candidates.isEmpty()) emptyList() else query(
            "SELECT run_id, test_id, outcome, retries, json_extract(payload, '$.className'), json_extract(payload, '$.name') " +
                "FROM observations WHERE run_id IN (${runs.joinToString { "?" }}) AND test_id IN (${candidates.joinToString { "?" }})",
            runs.map { it.id } + candidates.map { it.testId }) { r ->
                val runId = r.getString(1); val id = r.getString(2)
                NamedObservation(id, r.getString(5), r.getString(6), StabilityObservation(runId, Outcome.valueOf(r.getString(3)), r.getInt(4), evidence(runId, id)))
            }
        val byTest = observations.groupBy { it.testId }
        val complete = candidates.map { candidate ->
            val byRun = byTest.getValue(candidate.testId).associateBy { it.observation.runId }
            val latest = runs.asReversed().firstNotNullOf { byRun[it.id] }
            candidate.copy(className = latest.className, name = latest.name, history = runs.map { run ->
                byRun[run.id]?.observation ?: StabilityObservation(run.id, null, 0, null)
            })
        }
        return StabilityPage(head, window, minTransitions, runs, selectedSnapshot, totals[0], totals[1], totals[2], totals[3], totals[4], complete,
            if (offset.toLong() + limit < totals[4]) offset + limit else null)
    }

    private data class NamedObservation(val testId: String, val className: String, val name: String, val observation: StabilityObservation)
    private fun <T> query(sql: String, args: List<Any>, map: (ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
    }
}
