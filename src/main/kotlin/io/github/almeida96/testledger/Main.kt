package io.github.almeida96.testledger

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

private val usage = """
    TestLedger 0.1.0 — test-run evidence for MCP agents

    import   --reports DIR --id ID --project NAME --branch NAME --commit REF --at ISO_INSTANT
    runs     --project NAME --branch NAME [--offset 0] [--limit 20]
    summary  --run ID
    failures --run ID [--offset 0] [--limit 10]
    compare  --base ID --head ID [--offset 0] [--limit 50]
    history  --project NAME --branch NAME --test ID [--window 20]
    test     --run ID --test ID
    report   --base ID --head ID
    serve

    Every command accepts --db PATH (default: .testledger/ledger.db).
    serve speaks MCP over stdio. It exposes read-only tools and the triage-tests prompt.
    import reads JUnit XML; it does not execute a build. Data remains local unless an
    MCP client sends tool results to its model provider. No API key is needed here.
""".trimIndent()

fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
    try { execute(args) }
    catch (e: Exception) { System.err.println(Redactor.clean(e.message ?: "Command failed")); exitProcess(1) }
}

fun execute(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help"))) { println(usage); return }
    if (args.contentEquals(arrayOf("--version"))) { println("0.1.0"); return }
    val allowed = mapOf(
        "import" to setOf("reports", "id", "project", "branch", "commit", "at"),
        "runs" to setOf("project", "branch", "offset", "limit"), "summary" to setOf("run"),
        "failures" to setOf("run", "offset", "limit"), "compare" to setOf("base", "head", "offset", "limit"),
        "history" to setOf("project", "branch", "test", "window"), "test" to setOf("run", "test"),
        "report" to setOf("base", "head"), "serve" to emptySet(),
    )
    val command = args.first(); val options = allowed[command] ?: error("Unknown command: $command")
    require(args.size % 2 == 1) { "Options must use --name value" }
    val values = mutableMapOf<String, String>()
    args.drop(1).chunked(2).forEach { (flag, value) ->
        require(flag.startsWith("--") && (flag.drop(2) in options || flag == "--db")) { "Unknown option: $flag" }
        require(values.put(flag.drop(2), value) == null) { "Duplicate option: $flag" }
    }
    fun required(key: String) = values[key]?.takeIf { it.isNotBlank() } ?: error("Missing --$key")
    fun int(key: String, default: Int) = values[key]?.let { it.toIntOrNull() ?: error("--$key must be an integer") } ?: default
    val db = Path.of(values["db"] ?: ".testledger/ledger.db")
    // Parse every report before opening the writable ledger. An invalid batch cannot leave a partial run.
    if (command == "import") {
        val meta = RunMeta(required("id"), required("project"), required("branch"), required("commit"), required("at"))
        meta.validate()
        val batch = JunitReader().read(Path.of(required("reports")))
        Ledger(db, true).use { println(json.encodeToString(it.import(meta, batch))) }; return
    }
    Ledger(db).use { ledger ->
        when (command) {
            "serve" -> runBlocking { serve(ledger) }
            "runs" -> println(json.encodeToString(ledger.runs(required("project"), required("branch"), int("offset", 0), int("limit", 20))))
            "summary" -> println(json.encodeToString(ledger.summary(required("run"))))
            "failures" -> println(json.encodeToString(ledger.failures(required("run"), int("offset", 0), int("limit", 10))))
            "compare" -> println(json.encodeToString(ledger.compare(required("base"), required("head"), int("offset", 0), int("limit", 50))))
            "history" -> println(json.encodeToString(ledger.history(required("project"), required("branch"), required("test"), int("window", 20))))
            "test" -> println(json.encodeToString(ledger.test(required("run"), required("test"))))
            "report" -> println(markdownReport(ledger, required("base"), required("head")))
        }
    }
}

fun markdownReport(ledger: Ledger, base: String, head: String): String = buildString {
    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("|", "\\|").replace("\n", " ").replace("`", "'").replace("[", "\\[").replace("]", "\\]")
    val summary = ledger.summary(head)
    appendLine("# Test run $head")
    appendLine(); appendLine("Project: ${summary.run.project} · Branch: ${summary.run.branch} · Commit: ${summary.run.commit}")
    appendLine(); appendLine("${summary.tests} tests: ${summary.passed} passed, ${summary.failed} failed, ${summary.skipped} skipped. Testcases with retry hints: ${summary.retryHints}.")
    appendLine(); appendLine("Compared with $base. Missing and skipped tests are not counted as recovered.")
    appendLine(); appendLine("| Change | Test | Evidence |")
    appendLine("| --- | --- | --- |")
    var offset: Int? = 0
    while (offset != null) {
        val page = ledger.compare(base, head, offset, 100)
        for (change in page.changes) appendLine("| ${change.kind} | ${escape(change.name)} | ${change.evidence.joinToString(" ") { "`$it`" }} |")
        offset = page.nextOffset
    }
    appendLine(); appendLine("Evidence IDs resolve through the test_evidence MCP tool using the run and test IDs. This report describes observed outcomes; it does not determine root causes.")
}
