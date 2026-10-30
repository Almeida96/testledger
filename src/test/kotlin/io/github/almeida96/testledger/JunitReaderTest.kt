package io.github.almeida96.testledger

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JunitReaderTest {
    @TempDir lateinit var dir: Path
    private fun parse(body: String) = JunitReader().parse(body.toByteArray(), "TEST-demo.xml")
    private fun case(body: String = "", attributes: String = "") = parse("<testsuite name=\"demo\"><testcase name=\"works\" classname=\"Demo\" $attributes>$body</testcase></testsuite>").single()

    @Test fun `reads nested suites and all final outcomes with XML evidence lines`() {
        val rows = parse("""
            <testsuites><testsuite name="outer"><testsuite name="inner">
            <testcase name="pass" time="0.25"/>
            <testcase name="fail"><failure type="AssertionError" message="expected 2">stack</failure></testcase>
            <testcase name="error"><error type="IOException">unavailable</error></testcase>
            <testcase name="skip"><skipped/></testcase>
            </testsuite></testsuite></testsuites>
        """.trimIndent())
        assertEquals(listOf(Outcome.PASSED, Outcome.FAILURE, Outcome.ERROR, Outcome.SKIPPED), rows.map { it.outcome })
        assertEquals("outer / inner", rows.first().suite); assertEquals(2, rows.first().line); assertEquals(0.25, rows.first().seconds)
    }
    @Test fun `rejects external entities without resolving files`() {
        val secret = dir.resolve("secret.txt"); Files.writeString(secret, "private-data")
        assertFails { parse("""<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM "${secret.toUri()}">]><testsuite name="x"><testcase name="x"><failure>&xxe;</failure></testcase></testsuite>""") }
    }
    @Test fun `rejects DTD expansion and malformed XML`() {
        assertFails { parse("""<!DOCTYPE testsuite [<!ENTITY x "text">]><testsuite/>""") }
        assertFails { parse("<testsuite><testcase name=\"broken\"></testsuite>") }
        assertFails { parse("<html/>") }
    }
    @Test fun `captures retry hints without changing a successful final outcome`() {
        val row = case("<flakyFailure type=\"Timeout\"><stackTrace>timeout</stackTrace></flakyFailure><flakyFailure>again</flakyFailure>")
        assertEquals(Outcome.PASSED, row.outcome); assertEquals(2, row.retryFailures); assertNull(row.fingerprint)
    }
    @Test fun `retains a final failure when reruns also failed`() {
        val row = case("<failure type=\"Timeout\">first</failure><rerunFailure>second</rerunFailure>")
        assertEquals(Outcome.FAILURE, row.outcome); assertEquals(1, row.retryFailures); assertNotNull(row.fingerprint)
    }
    @Test fun `never stores suite properties or stdout`() {
        val row = parse("""<testsuite name="x"><properties><property name="password" value="private"/></properties><system-out>private</system-out><testcase name="x"><system-out>private</system-out></testcase></testsuite>""").single()
        assertFalse(json.encodeToString(Observation.serializer(), row).contains("private"))
    }
    @Test fun `redacts credential patterns before keeping failure evidence`() {
        val row = case("""<failure type="Error" message="password=topsecret">Authorization: Bearer abcdef123456
            token=opaque-value https://alice:secret@example.invalid/path sk-12345678901234567890</failure>""")
        val data = json.encodeToString(Observation.serializer(), row)
        for (secret in listOf("topsecret", "abcdef123456", "opaque-value", "alice:secret", "sk-12345678901234567890")) assertFalse(data.contains(secret), secret)
        assertContains(data, "[REDACTED]")
    }
    @Test fun `bounds failure text and reports truncation`() {
        val row = case("<failure>${"x".repeat(20000)}</failure>")
        assertTrue(row.truncated); assertTrue(row.detail.length <= 12000)
    }
    @Test fun `rejects missing names invalid durations and contradictory outcomes`() {
        for (time in listOf("NaN", "Infinity", "-1", "oops")) assertFails { case(attributes = "time=\"$time\"") }
        assertFails { parse("<testsuite><testcase/></testsuite>") }
        assertFails { case("<skipped/><failure/>") }
    }
    @Test fun `duplicate identities fail the complete import batch`() {
        Files.writeString(dir.resolve("TEST-x.xml"), "<testsuite><testcase name=\"same\"/><testcase name=\"same\"/></testsuite>")
        assertFailsWith<IllegalArgumentException> { JunitReader().read(dir) }
    }
    @Test fun `identity includes the report module and parameterized test name`() {
        val xml = "<testsuite name=\"suite\"><testcase classname=\"C\" name=\"case[1]\"/></testsuite>"
        val a = JunitReader().parse(xml.toByteArray(), "a/TEST.xml").single()
        val b = JunitReader().parse(xml.toByteArray(), "b/TEST.xml").single()
        val c = parse(xml.replace("[1]", "[2]")).single()
        assertEquals(3, setOf(a.testId, b.testId, c.testId).size)
    }
    @Test fun `fingerprints group matching frames despite line changes`() {
        val a = fingerprint("AssertionError", "at org.junit.Assert.check(Assert.java:1)\nat app.Checkout.total(Checkout.kt:10)", "expected 1")
        val b = fingerprint("AssertionError", "at app.Checkout.total(Checkout.kt:20)", "expected 2")
        assertEquals(a, b)
        assertNotEquals(a, fingerprint("Timeout", "at app.Checkout.total(Checkout.kt:20)", "expected 2"))
    }
    @Test fun `skips hidden and linked reports and rejects oversized files`() {
        Files.writeString(dir.resolve(".hidden.xml"), "not xml")
        Files.writeString(dir.resolve("TEST-good.xml"), "<testsuite/>")
        assertEquals(1, JunitReader().read(dir).reportCount)
        Files.writeString(dir.resolve("TEST-big.xml"), "x".repeat(2 * 1024 * 1024 + 1))
        assertFails { JunitReader().read(dir) }
    }
    @Test fun `empty directory and excessive XML depth fail clearly`() {
        assertFails { JunitReader().read(dir) }
        assertFails { parse("<testsuite>".repeat(65) + "</testsuite>".repeat(65)) }
    }
}
