package io.github.almeida96.testledger

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

const val VERSION = "0.2.0"
val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Serializable
enum class Outcome { PASSED, FAILURE, ERROR, SKIPPED;
    val failed: Boolean get() = this == FAILURE || this == ERROR
}

@Serializable
data class Observation(
    val testId: String,
    val report: String,
    val line: Int,
    val suite: String,
    val className: String,
    val name: String,
    val outcome: Outcome,
    val seconds: Double,
    val failureType: String,
    val message: String,
    val detail: String,
    val fingerprint: String?,
    val retryFailures: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
data class RunMeta(val id: String, val project: String, val branch: String, val commit: String, val at: String) {
    fun validate() {
        for ((label, value) in listOf("id" to id, "project" to project, "branch" to branch, "commit" to commit)) {
            require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/+-]{0,127}"))) { "Invalid $label: use 1–128 letters, digits, dot, underscore, slash, plus or dash" }
        }
        Instant.parse(at)
    }
}

@Serializable
data class ImportResult(val run: RunMeta, val inserted: Boolean, val reports: Int, val tests: Int, val digest: String)
@Serializable
data class RunSummary(val run: RunMeta, val tests: Int, val passed: Int, val failed: Int, val skipped: Int, val retryHints: Int)
@Serializable
data class RunPage(val runs: List<RunSummary>, val nextOffset: Int?)
@Serializable
data class TestRef(val testId: String, val className: String, val name: String, val outcome: Outcome, val evidence: String)
@Serializable
data class FailureGroup(val fingerprint: String, val failureType: String, val count: Int, val examples: List<TestRef>)
@Serializable
data class FailurePage(val runId: String, val groups: List<FailureGroup>, val totalGroups: Int, val nextOffset: Int?)
@Serializable
data class Change(val kind: String, val testId: String, val name: String, val before: Outcome?, val after: Outcome?, val evidence: List<String>)
@Serializable
data class Comparison(val base: String, val head: String, val counts: Map<String, Int>, val changes: List<Change>, val nextOffset: Int?)
@Serializable
data class HistoryItem(val run: RunMeta, val outcome: Outcome?, val retryFailures: Int, val evidence: String?)
@Serializable
data class TestHistory(val testId: String, val scope: String, val observed: Int, val missing: Int, val transitions: Int, val intermittent: Boolean, val note: String, val history: List<HistoryItem>)

fun evidence(runId: String, testId: String): String = "testledger://runs/$runId/tests/$testId"

object Redactor {
    private val assignments = Regex("(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|authorization)\\b[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\s\\\"',;<>]+)")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val urlCredentials = Regex("(https?://)[^/\\s:@]+:[^/\\s@]+@")
    private val knownTokens = Regex("\\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{16,})\\b")
    fun clean(text: String): String {
        val safe = text.filter { it == '\n' || it == '\t' || !it.isISOControl() }
        return knownTokens.replace(urlCredentials.replace(assignments.replace(bearer.replace(safe, "Bearer [REDACTED]")) { it.groupValues[1] + "[REDACTED]" }, "$1[REDACTED]@"), "[REDACTED]")
    }
}

fun fingerprint(type: String, detail: String, message: String): String {
    val frame = detail.lineSequence().map { it.trim() }.firstOrNull {
        it.startsWith("at ") && !it.matches(Regex("at (?:java\\.|jdk\\.|sun\\.|kotlin\\.|org\\.junit\\.|org\\.gradle\\.|org\\.apache\\.maven\\.).*"))
    }
    val signature = frame?.replace(Regex(":\\d+\\)"), ":#)")
        ?: message.replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}"), "<id>").replace(Regex("\\b\\d+\\b"), "#")
    return sha256("$type\n$signature").take(24)
}
