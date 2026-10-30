package io.github.almeida96.testledger

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants as X

data class ReportBatch(val tests: List<Observation>, val reportCount: Int, val digest: String)

class JunitReader {
    fun read(root: Path): ReportBatch {
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Reports root must be a directory, not a symlink" }
        val canonical = root.toRealPath()
        val files = mutableListOf<Path>()
        fun walk(directory: Path, depth: Int) {
            require(depth <= 32) { "Report directory nesting exceeds 32 levels" }
            Files.newDirectoryStream(directory).use { entries ->
                for (entry in entries.sortedBy { it.fileName.toString() }) {
                    if (Files.isSymbolicLink(entry) || entry.fileName.toString().startsWith('.')) continue
                    require(entry.toRealPath().startsWith(canonical)) { "Report escapes the selected root" }
                    if (Files.isDirectory(entry, NOFOLLOW_LINKS)) walk(entry, depth + 1)
                    else if (Files.isRegularFile(entry, NOFOLLOW_LINKS) && entry.fileName.toString().endsWith(".xml", true)) {
                        require(files.size < 1000) { "Report limit exceeded (1,000 XML files)" }
                        files.add(entry)
                    }
                }
            }
        }
        walk(canonical, 0)
        require(files.isNotEmpty()) { "No XML reports found" }
        val observations = mutableListOf<Observation>()
        val digests = mutableListOf<String>()
        var total = 0L
        for (file in files) {
            val path = canonical.relativize(file).toString().replace('\\', '/')
            require(path.length <= 500) { "Report path exceeds 500 characters" }
            val size = Files.size(file)
            require(size <= 2 * 1024 * 1024) { "Report exceeds 2 MiB: $path" }
            total += size
            require(total <= 25 * 1024 * 1024) { "Reports exceed 25 MiB" }
            val bytes = Files.newInputStream(file).use { it.readNBytes(2 * 1024 * 1024 + 1) }
            require(bytes.size.toLong() == size) { "Report changed during import: $path; retry" }
            observations += parse(bytes, path)
            require(observations.size <= 25000) { "Test limit exceeded (25,000 cases)" }
            digests += "$path\u0000${sha256(bytes.decodeToString(throwOnInvalidSequence = true))}"
        }
        require(observations.map { it.testId }.toSet().size == observations.size) {
            "Duplicate test identity (report path, suite, class and name). Export distinct parameterized test names."
        }
        return ReportBatch(observations, files.size, sha256(digests.sorted().joinToString("\n")))
    }

    fun parse(bytes: ByteArray, report: String): List<Observation> {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setXMLResolver { _, _, _, _ -> throw IllegalArgumentException("External XML references are disabled") }
        }
        val xml = factory.createXMLStreamReader(ByteArrayInputStream(bytes), "UTF-8")
        val result = mutableListOf<Observation>()
        val suites = mutableListOf<String>()
        var depth = 0
        var rootSeen = false
        var current: CaseBuilder? = null
        var captureDepth = -1
        var captureRetry = false
        try {
            while (xml.hasNext()) {
                when (xml.next()) {
                    X.DTD, X.ENTITY_REFERENCE -> error("DTD and entity references are not accepted")
                    X.START_ELEMENT -> {
                        depth++
                        require(depth <= 64) { "XML nesting exceeds 64 levels" }
                        val tag = xml.localName
                        fun attr(name: String, fallback: String = ""): String = (xml.getAttributeValue(null, name) ?: fallback).also {
                            require(it.length <= 4000) { "XML attribute exceeds 4,000 characters" }
                        }
                        if (!rootSeen) {
                            require(tag == "testsuite" || tag == "testsuites") { "Expected a JUnit testsuite or testsuites root" }
                            rootSeen = true
                        }
                        when (tag) {
                            "testsuite" -> { require(current == null) { "Nested suite inside a testcase" }; suites += attr("name", "unnamed") }
                            "testcase" -> {
                                require(current == null && suites.isNotEmpty()) { "Invalid nested testcase" }
                                val name = attr("name"); require(name.isNotBlank()) { "Testcase is missing a name" }
                                require(name.length <= 512 && attr("classname").length <= 512 && suites.joinToString(" / ").length <= 1024) { "Test identity exceeds its length limit" }
                                val seconds = attr("time", "0").toDoubleOrNull() ?: error("Invalid testcase duration")
                                require(seconds.isFinite() && seconds >= 0) { "Invalid testcase duration" }
                                current = CaseBuilder(report, xml.location.lineNumber, suites.joinToString(" / "), attr("classname"), name, seconds)
                            }
                            "failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError" -> {
                                val item = current ?: error("Outcome outside a testcase")
                                require(captureDepth == -1) { "Nested failure elements" }
                                if (tag == "skipped") {
                                    require(!item.outcome.failed) { "Testcase cannot be both skipped and failed" }; item.outcome = Outcome.SKIPPED
                                } else {
                                    captureRetry = tag.startsWith("flaky") || tag.startsWith("rerun")
                                    if (captureRetry) item.retries++ else {
                                        require(item.outcome != Outcome.SKIPPED) { "Testcase cannot be both skipped and failed" }
                                        item.outcome = if (tag == "error") Outcome.ERROR else Outcome.FAILURE
                                    }
                                    captureDepth = depth
                                    if (!captureRetry || item.type.isEmpty()) {
                                        item.type = attr("type", tag); item.message = attr("message")
                                    }
                                }
                            }
                        }
                    }
                    X.CHARACTERS, X.CDATA -> if (captureDepth >= 0) {
                        val item = current!!
                        val remaining = 12000 - item.detail.length
                        if (xml.text.length > remaining) item.truncated = true
                        item.detail.append(xml.text.take(remaining.coerceAtLeast(0)))
                    }
                    X.END_ELEMENT -> {
                        if (depth == captureDepth) { current!!.detail.append('\n'); captureDepth = -1 }
                        when (xml.localName) {
                            "testcase" -> {
                                result += current!!.finish(); current = null
                                require(result.size <= 25000) { "Test limit exceeded" }
                            }
                            "testsuite" -> suites.removeLast()
                        }
                        depth--
                    }
                }
            }
            require(rootSeen) { "Empty XML document" }
        } finally { xml.close() }
        return result
    }

    private class CaseBuilder(val report: String, val line: Int, val suite: String, val className: String, val name: String, val seconds: Double) {
        var outcome = Outcome.PASSED
        var type = ""
        var message = ""
        val detail = StringBuilder()
        var retries = 0
        var truncated = false
        fun finish(): Observation {
            val cleanType = Redactor.clean(type)
            val cleanMessage = Redactor.clean(message)
            val cleanDetail = Redactor.clean(detail.toString().trim())
            return Observation(
                sha256(listOf(report, suite, className, name).joinToString("\u0000")).take(24),
                Redactor.clean(report), line, Redactor.clean(suite), Redactor.clean(className), Redactor.clean(name),
                outcome, seconds, cleanType, cleanMessage, cleanDetail,
                if (outcome.failed) fingerprint(cleanType, cleanDetail, cleanMessage) else null, retries, truncated,
            )
        }
    }
}
